package br.com.seuorg.ans;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class App {

    private static final String FRASE_DESPESAS = "Despesas com Eventos/Sinistros";
    private static final Map<String, List<String>> COLUNAS_ALIAS = Map.of(
            "CNPJ", List.of("CNPJ", "CNPJ_OPERADORA", "CNPJ DA OPERADORA", "CNPJ OPERADORA"),
            "RazaoSocial", List.of("RAZAO SOCIAL", "RAZÃO SOCIAL", "NOME", "NOME_OPERADORA", "RAZAO_SOCIAL", "RAZAO SOCIAL DA OPERADORA", "RAZAO SOCIAL OPERADORA"),
            "Trimestre", List.of("TRIMESTRE", "TRIM", "TRIMESTRE_REFERENCIA", "TRIMESTRE REFERENCIA"),
            "Ano", List.of("ANO", "ANO_REFERENCIA", "ANO REF", "ANO_REFERENCIA"),
            "ValorDespesas", List.of("VALOR DESPESAS", "VL_DESPESA", "VL_DESPESAS", "VALOR", "VALOR_EVENTOS_SINISTROS", "DESPESAS", "DESPESAS COM EVENTOS SINISTROS", "DESPESA EVENTOS SINISTROS", "DESPESAS EVENTOS SINISTROS")
    );
    private static final List<String> COLUNAS_SAIDA = List.of(
            "CNPJ",
            "RazaoSocial",
            "Trimestre",
            "Ano",
            "ValorDespesas"
    );

    public static void main(String[] args) throws Exception {

        Path baseDir = Paths.get("target", "ans");
        Files.createDirectories(baseDir);

        TrimestresFinder finder = new TrimestresFinder();
        List<TrimestresFinder.TrimestreInfo> recentes = finder.findRecentTrimestres(3);
        if (recentes.isEmpty()) {
            throw new IllegalStateException("Nenhum trimestre encontrado no FTP da ANS.");
        }

        System.out.println("Trimestres mais recentes encontrados:");
        for (TrimestresFinder.TrimestreInfo info : recentes) {
            System.out.println(info.trimestre() + " -> " + info.urls());
        }

        Path csvSaida = baseDir.resolve("consolidado_despesas.csv");
        Path zipSaida = baseDir.resolve("consolidado_despesas.zip");

        System.out.println("Gerando consolidado em " + csvSaida + "...");
        gerarConsolidado(recentes, baseDir, csvSaida);
        System.out.println("Compactando consolidado em " + zipSaida + "...");
        compactarCsv(csvSaida, zipSaida);
    }

    private static void download(String url, Path dest) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        Files.copy(response.body(), dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName()).normalize();
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static List<Path> findArquivosComDespesas(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            List<Path> arquivos = new ArrayList<>();
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                if (fileContainsPhrase(path, FRASE_DESPESAS)) {
                    arquivos.add(path);
                }
            }
            if (arquivos.isEmpty()) {
                throw new IllegalStateException("Nenhum arquivo com \"" + FRASE_DESPESAS + "\" encontrado.");
            }
            return arquivos;
        }
    }

    private static void parseArquivoDespesas(Path arquivo, Consumer<DespesaEvento> consumer) throws IOException, InvalidFormatException {
        FileType tipo = detectFileType(arquivo);
        switch (tipo) {
            case CSV, TXT -> parseDelimitedFile(arquivo, consumer);
            case XLSX -> parseXlsxFile(arquivo, consumer);
            default -> {
            }
        };
    }

    private static FileType detectFileType(Path arquivo) {
        String nome = arquivo.getFileName().toString().toLowerCase(Locale.ROOT);
        if (nome.endsWith(".csv")) {
            return FileType.CSV;
        }
        if (nome.endsWith(".txt")) {
            return FileType.TXT;
        }
        try (InputStream in = Files.newInputStream(arquivo)) {
            try (Workbook ignored = WorkbookFactory.create(in)) {
                return FileType.XLSX;
            }
        } catch (IOException ignored) {
        }
        return FileType.UNKNOWN;
    }

    private static boolean fileContainsPhrase(Path arquivo, String phrase) {
        FileType tipo = detectFileType(arquivo);
        try {
            return switch (tipo) {
                case CSV, TXT -> fileContainsPhraseInText(arquivo, phrase);
                case XLSX -> fileContainsPhraseInXlsx(arquivo, phrase);
                default -> false;
            };
        } catch (IOException | InvalidFormatException e) {
            return false;
        }
    }

    private static boolean fileContainsPhraseInText(Path arquivo, String phrase) throws IOException {
        CsvMapper mapper = new CsvMapper();
        char separador = detectarSeparador(arquivo);
        CsvSchema schema = CsvSchema.emptySchema()
                .withHeader()
                .withColumnSeparator(separador);

        try (Reader reader = Files.newBufferedReader(arquivo, detectCharset(arquivo))) {
            MappingIterator<Map<String, String>> it =
                    mapper.readerFor(Map.class)
                            .with(schema)
                            .readValues(reader);
            String colunaDescricao = null;
            while (it.hasNext()) {
                Map<String, String> row = it.next();
                if (colunaDescricao == null) {
                    colunaDescricao = findDescricaoHeader(row.keySet());
                    if (colunaDescricao == null) {
                        return false;
                    }
                }
                String descricao = row.get(colunaDescricao);
                if (descricao != null && descricao.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean fileContainsPhraseInXlsx(Path arquivo, String phrase) throws IOException, InvalidFormatException {
        try (InputStream in = Files.newInputStream(arquivo);
             Workbook workbook = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter();
            for (Sheet sheet : workbook) {
                Iterator<Row> rows = sheet.iterator();
                if (!rows.hasNext()) {
                    continue;
                }
                Row headerRow = rows.next();
                Integer colunaDescricao = null;
                for (Cell cell : headerRow) {
                    String header = formatter.formatCellValue(cell);
                    if (normalizeHeader("DESCRICAO").equals(normalizeHeader(header))) {
                        colunaDescricao = cell.getColumnIndex();
                        break;
                    }
                }
                if (colunaDescricao == null) {
                    continue;
                }
                while (rows.hasNext()) {
                    Row row = rows.next();
                    Cell cell = row.getCell(colunaDescricao);
                    String value = cell == null ? null : formatter.formatCellValue(cell);
                    if (value != null && value.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static void parseDelimitedFile(Path arquivo, Consumer<DespesaEvento> consumer) throws IOException {
        CsvMapper mapper = new CsvMapper();
        char separador = detectarSeparador(arquivo);
        CsvSchema schema = CsvSchema.emptySchema()
                .withHeader()
                .withColumnSeparator(separador);

        try (Reader reader = Files.newBufferedReader(arquivo, detectCharset(arquivo))) {
            MappingIterator<Map<String, String>> it =
                    mapper.readerFor(Map.class)
                            .with(schema)
                            .readValues(reader);

            Map<String, String> headerLookup = null;
            while (it.hasNext()) {
                Map<String, String> row = it.next();
                if (headerLookup == null) {
                    headerLookup = buildHeaderLookup(row.keySet());
                }
                DespesaEvento evento = mapRow(row, headerLookup);
                if (evento != null) {
                    consumer.accept(evento);
                }
            }
        }
    }

    private static void parseXlsxFile(Path arquivo, Consumer<DespesaEvento> consumer) throws IOException, InvalidFormatException {
        try (InputStream in = Files.newInputStream(arquivo);
             Workbook workbook = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter();
            for (Sheet sheet : workbook) {
                Iterator<Row> rows = sheet.iterator();
                if (!rows.hasNext()) {
                    continue;
                }
                Row headerRow = rows.next();
                Map<Integer, String> headerPorColuna = new HashMap<>();
                for (Cell cell : headerRow) {
                    headerPorColuna.put(cell.getColumnIndex(), formatter.formatCellValue(cell));
                }
                Map<String, String> headerLookup = buildHeaderLookup(headerPorColuna.values());
                while (rows.hasNext()) {
                    Row row = rows.next();
                    Map<String, String> values = new HashMap<>();
                    for (Map.Entry<Integer, String> entry : headerPorColuna.entrySet()) {
                        Cell cell = row.getCell(entry.getKey());
                        String value = cell == null ? null : formatter.formatCellValue(cell);
                        values.put(entry.getValue(), value);
                    }
                    DespesaEvento evento = mapRow(values, headerLookup);
                    if (evento != null) {
                        consumer.accept(evento);
                    }
                }
            }
        }
    }

    private static DespesaEvento mapRow(Map<String, String> row, Map<String, String> headerLookup) {
        String cnpj = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("CNPJ"));
        String razaoSocial = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("RazaoSocial"));
        String trimestre = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Trimestre"));
        String anoStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Ano"));
        String valorStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("ValorDespesas"));
        if (cnpj == null && razaoSocial == null && trimestre == null && anoStr == null && valorStr == null) {
            return null;
        }
        DespesaEvento evento = new DespesaEvento();
        evento.setCnpj(normalizeValue(cnpj));
        evento.setRazaoSocial(normalizeValue(razaoSocial));
        evento.setTrimestre(normalizeValue(trimestre));
        evento.setAno(parseInteger(normalizeValue(anoStr)));
        evento.setValorDespesas(parseBigDecimal(normalizeValue(valorStr)));
        return evento;
    }

    private static Map<String, String> buildHeaderLookup(Collection<String> headers) {
        Map<String, String> lookup = new HashMap<>();
        for (String header : headers) {
            if (header == null) {
                continue;
            }
            lookup.put(normalizeHeader(header), header);
        }
        return lookup;
    }

    private static String findDescricaoHeader(Collection<String> headers) {
        String target = normalizeHeader("DESCRICAO");
        for (String header : headers) {
            if (header == null) {
                continue;
            }
            if (target.equals(normalizeHeader(header))) {
                return header;
            }
        }
        return null;
    }

    private static String getValueByAliases(Map<String, String> row, Map<String, String> headerLookup, List<String> aliases) {
        if (aliases == null) {
            return null;
        }
        for (String alias : aliases) {
            String normalized = normalizeHeader(alias);
            String header = headerLookup.get(normalized);
            if (header != null) {
                String value = row.get(header);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            String valueFromSimilar = findValueBySimilarHeader(row, headerLookup, normalized);
            if (valueFromSimilar != null) {
                return valueFromSimilar;
            }
        }
        return null;
    }

    private static String findValueBySimilarHeader(Map<String, String> row,
                                                   Map<String, String> headerLookup,
                                                   String normalizedAlias) {
        for (Map.Entry<String, String> entry : headerLookup.entrySet()) {
            String normalizedHeader = entry.getKey();
            if (normalizedHeader.contains(normalizedAlias) || normalizedAlias.contains(normalizedHeader)) {
                String value = row.get(entry.getValue());
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String normalizeHeader(String header) {
        String normalized = Normalizer.normalize(header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        return normalized.replaceAll("[^A-Z0-9]", "");
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseBigDecimal(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace(".", "").replace(",", ".");
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static char detectarSeparador(Path arquivo) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(arquivo, detectCharset(arquivo))) {
            String header = reader.readLine();
            if (header == null) {
                return ';';
            }
            if (header.contains(";")) {
                return ';';
            }
            if (header.contains("\t")) {
                return '\t';
            }
            return ',';
        }
    }

    private static Charset detectCharset(Path arquivo) throws IOException {
        try (InputStream in = Files.newInputStream(arquivo)) {
            byte[] bom = in.readNBytes(3);
            if (bom.length >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void gerarConsolidado(List<TrimestresFinder.TrimestreInfo> trimestres,
                                         Path baseDir,
                                         Path csvSaida) throws Exception {
        CsvMapper mapper = new CsvMapper();
        CsvSchema.Builder schemaBuilder = CsvSchema.builder();
        for (String coluna : COLUNAS_SAIDA) {
            schemaBuilder.addColumn(coluna);
        }
        CsvSchema schema = schemaBuilder.setUseHeader(true).build();

        try (Writer writer = Files.newBufferedWriter(csvSaida, StandardCharsets.UTF_8);
             SequenceWriter sequenceWriter = mapper.writer(schema).writeValues(writer)) {
            for (TrimestresFinder.TrimestreInfo info : trimestres) {
                processarTrimestre(info, baseDir, evento -> escreverEvento(sequenceWriter, evento));
            }
        }
    }

    private static void processarTrimestre(TrimestresFinder.TrimestreInfo info,
                                           Path baseDir,
                                           Consumer<DespesaEvento> consumer) throws Exception {
        for (URI zipUrl : info.urls()) {
            String zipName = Paths.get(zipUrl.getPath()).getFileName().toString();
            Path zip = baseDir.resolve(info.trimestre() + "-" + zipName);
            Path extractDir = baseDir.resolve(info.trimestre()).resolve(zipName.replace(".zip", ""));

            System.out.println("Baixando ZIP " + zipUrl + "...");
            download(zipUrl.toString(), zip);

            System.out.println("Extraindo ZIP " + zip + "...");
            unzip(zip, extractDir);

            List<Path> arquivos = findArquivosComDespesas(extractDir);
            System.out.println("Arquivos encontrados com \"" + FRASE_DESPESAS + "\" em " + info.trimestre() + ": " + arquivos.size());
            for (Path arquivo : arquivos) {
                parseArquivoDespesas(arquivo, consumer);
            }
        }
    }

    private static void escreverEvento(SequenceWriter writer, DespesaEvento evento) {
        Map<String, Object> linha = new LinkedHashMap<>();
        linha.put("CNPJ", evento.getCnpj());
        linha.put("RazaoSocial", evento.getRazaoSocial());
        linha.put("Trimestre", evento.getTrimestre());
        linha.put("Ano", evento.getAno());
        linha.put("ValorDespesas", evento.getValorDespesas() == null ? null : evento.getValorDespesas().toPlainString());
        try {
            writer.write(linha);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void compactarCsv(Path csv, Path zipDestino) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipDestino))) {
            ZipEntry entry = new ZipEntry(csv.getFileName().toString());
            zos.putNextEntry(entry);
            Files.copy(csv, zos);
            zos.closeEntry();
        }
    }

    private enum FileType {
        CSV,
        TXT,
        XLSX,
        UNKNOWN
    }
}
