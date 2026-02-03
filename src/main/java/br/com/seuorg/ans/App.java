package br.com.seuorg.ans;

import br.com.seuorg.ans.service.TransformacaoService;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class App {

    private static final Logger LOGGER = Logger.getLogger(App.class.getName());
    private static final String FRASE_DESPESAS = "Despesas com Eventos/Sinistros";
    private static final Pattern EVENTOS_SINISTROS_PATTERN = Pattern.compile("\\b(eventos|sinistros)\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> CONTAS_EVENTOS_SINISTROS_PREFIXOS = List.of("3.04.01.04");
    private static final Map<String, List<String>> COLUNAS_ALIAS = Map.of(
            "RegistroANS", List.of("REG_ANS", "REGANS", "REGISTRO ANS"),
            "CNPJ", List.of("CNPJ", "CNPJ_OPERADORA", "CNPJ OPERADORA"),
            "RazaoSocial", List.of("RAZAO SOCIAL", "RAZÃO SOCIAL", "NOME OPERADORA", "OPERADORA"),
            "Data", List.of("DATA", "DT_REF", "DATA_REFERENCIA", "DATA REFERENCIA"),
            "ContaContabil", List.of("CD_CONTA_CONTABIL", "CONTA_CONTABIL", "CONTA CONTABIL"),
            "Descricao", List.of("DESCRICAO", "DESCR", "DESCRIÇÃO"),
            "ValorDespesas", List.of("VL_SALDO_FINAL", "VL_SALDO", "VALOR", "VALOR DESPESA", "VL_DESPESA")
    );
    private static final List<String> COLUNAS_SAIDA = List.of(
            "CNPJ",
            "RegistroANS",
            "Ano",
            "Trimestre",
            "ValorDespesas"
    );
    private static final List<DateTimeFormatter> FORMATOS_DATA = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu")
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
        Path csvAgregado = baseDir.resolve("despesas_agregadas.csv");

        System.out.println("Gerando consolidado em " + csvSaida + "...");
        gerarConsolidado(recentes, baseDir, csvSaida);
        System.out.println("Compactando consolidado em " + zipSaida + "...");
        compactarCsv(csvSaida, zipSaida);
        System.out.println("Gerando agregado em " + csvAgregado + "...");
        new TransformacaoService().gerarDespesasAgregadas(baseDir, csvSaida, csvAgregado);
        System.out.println("Processamento concluído: " + csvAgregado + " gerado com sucesso");
    }

    private static void download(String url, Path dest) throws Exception {
        if (Files.exists(dest)) {
            System.out.println("Arquivo já existe, pulando download: " + dest.getFileName());
            return;
        }
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
        if (Files.exists(dest)) {
            try (var stream = Files.walk(dest)) {
                if (stream.anyMatch(Files::isRegularFile)) {
                    System.out.println("Pasta já contém arquivos, pulando extração: " + dest.getFileName());
                    return;
                }
            }
        }
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
                if (detectFileType(path) != FileType.UNKNOWN) {
                    arquivos.add(path);
                }
            }
            if (arquivos.isEmpty()) {
                throw new IllegalStateException("Nenhum arquivo de despesas encontrado.");
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

    private static void parseDelimitedFile(Path arquivo, Consumer<DespesaEvento> consumer) throws IOException {
        char separador = detectarSeparador(arquivo);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(separador)
                .setQuote('"')
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Reader reader = Files.newBufferedReader(arquivo, detectCharset(arquivo));
             CSVParser parser = format.parse(reader)) {
            Map<String, String> headerLookup = buildHeaderLookup(parser.getHeaderMap().keySet());
            for (CSVRecord record : parser) {
                Map<String, String> row = new HashMap<>();
                for (String header : parser.getHeaderMap().keySet()) {
                    row.put(header, record.isMapped(header) ? record.get(header) : null);
                }
                DespesaEvento evento = mapRow(row, headerLookup);
                if (evento != null) {
                    consumer.accept(evento);
                } else {
                    LOGGER.log(Level.FINE, "Linha ignorada por inconsistência: {0} em {1}",
                            new Object[]{record.getRecordNumber(), arquivo.getFileName()});
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
        String registroAns = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("RegistroANS"));
        String dataStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Data"));
        String contaContabil = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("ContaContabil"));
        String descricao = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Descricao"));
        String valorStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("ValorDespesas"));
        if (cnpj == null && registroAns == null && dataStr == null && contaContabil == null && descricao == null && valorStr == null) {
            return null;
        }
        if (!isDespesaEvento(contaContabil, descricao)) {
            return null;
        }
        LocalDate dataReferencia = parseData(dataStr);
        if (dataReferencia == null) {
            return null;
        }
        DespesaEvento evento = new DespesaEvento();
        evento.setCnpj(normalizeCnpj(cnpj));
        evento.setRegistroAns(normalizeValue(registroAns));
        evento.setAno(dataReferencia.getYear());
        evento.setTrimestre(formatTrimestre(dataReferencia));
        evento.setValorDespesas(parseBigDecimal(normalizeValue(valorStr)));
        if (evento.getValorDespesas() == null) {
            return null;
        }
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

    private static String normalizeCnpj(String value) {
        String normalized = normalizeValue(value);
        if (normalized == null) {
            return null;
        }
        String digits = normalized.replaceAll("\\D", "");
        return digits.length() == 14 ? digits : null;
    }

    private static LocalDate parseData(String value) {
        String normalized = normalizeValue(value);
        if (normalized == null) {
            return null;
        }
        String normalized = normalizeNumber(value);
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static String trimestreFromMonth(int month) {
        int trimestre = (month - 1) / 3 + 1;
        return trimestre + "T";
    }

    private static boolean isDespesaEvento(String contaContabil, String descricao) {
        String descricaoValor = descricao == null ? "" : descricao;
        if (EVENTOS_SINISTROS_PATTERN.matcher(descricaoValor).find()) {
            return true;
        }
        String contaNormalizada = normalizeHeader(contaContabil == null ? "" : contaContabil);
        // Critério adotado: aceitar registros cuja descrição contenha "Eventos" ou "Sinistros"
        // OU cuja conta contábil esteja sob o prefixo 3.04.01.04 (contas de eventos/sinistros).
        for (String prefixo : CONTAS_EVENTOS_SINISTROS_PREFIXOS) {
            if (contaNormalizada.startsWith(normalizeHeader(prefixo))) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate parseData(String value) {
        String normalized = normalizeValue(value);
        if (normalized == null) {
            return null;
        }
        for (DateTimeFormatter formatter : FORMATOS_DATA) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static String formatTrimestre(LocalDate data) {
        int trimestre = (data.getMonthValue() - 1) / 3 + 1;
        return data.getYear() + "_" + trimestre + "_trimestre";
    }

    private static boolean isDespesaEvento(String contaContabil, String descricao) {
        String descricaoValor = descricao == null ? "" : descricao;
        if (EVENTOS_SINISTROS_PATTERN.matcher(descricaoValor).find()) {
            return true;
        }
        String contaNormalizada = normalizeHeader(contaContabil == null ? "" : contaContabil);
        for (String prefixo : CONTAS_EVENTOS_SINISTROS_PREFIXOS) {
            if (contaNormalizada.startsWith(normalizeHeader(prefixo))) {
                return true;
            }
        }
        return false;
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
        byte[] sample;
        try (InputStream in = Files.newInputStream(arquivo)) {
            sample = in.readNBytes(64 * 1024);
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(sample));
            return StandardCharsets.UTF_8;
        } catch (Exception ignored) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static String normalizeNumber(String value) {
        String trimmed = normalizeValue(value);
        if (trimmed == null) {
            return null;
        }
        String cleaned = trimmed.replace(" ", "");
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            char decimalSep = lastComma > lastDot ? ',' : '.';
            String withoutThousands = cleaned.replace(decimalSep == ',' ? "." : ",", "");
            return withoutThousands.replace(decimalSep, '.');
        }
        if (lastComma >= 0) {
            String withoutThousands = cleaned.replace(".", "");
            return withoutThousands.replace(',', '.');
        }
        if (lastDot >= 0) {
            String withoutThousands = cleaned.replace(",", "");
            return withoutThousands;
        }
        return cleaned;
    }

    private static void gerarConsolidado(List<TrimestresFinder.TrimestreInfo> trimestres,
                                         Path baseDir,
                                         Path csvSaida) throws Exception {
        CsvMapper mapper = new CsvMapper();
        CsvSchema.Builder schemaBuilder = CsvSchema.builder();
        for (String coluna : COLUNAS_SAIDA) {
            schemaBuilder.addColumn(coluna);
        }
        CsvSchema schema = schemaBuilder.setUseHeader(true)
                .setColumnSeparator(';')
                .setQuoteChar('"')
                .build();

        Set<String> dedupe = new HashSet<>();
        try (Writer writer = Files.newBufferedWriter(csvSaida, StandardCharsets.UTF_8);
             SequenceWriter sequenceWriter = mapper.writer(schema).writeValues(writer)) {
            for (TrimestresFinder.TrimestreInfo info : trimestres) {
                processarTrimestre(info, baseDir, evento -> {
                    if (evento.getValorDespesas() == null
                            || evento.getValorDespesas().signum() <= 0) {
                        LOGGER.log(Level.WARNING, "Registro suspeito (valor <= 0) ignorado. CNPJ={0}, Trimestre={1}",
                                new Object[]{evento.getCnpj(), evento.getTrimestre()});
                        return;
                    }
                    String chave = buildDedupeKey(evento);
                    if (!dedupe.add(chave)) {
                        LOGGER.log(Level.WARNING, "Registro duplicado ignorado: {0}", chave);
                        return;
                    }
                    escreverEvento(sequenceWriter, evento);
                });
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
            System.out.println("Arquivos encontrados em " + info.trimestre() + ": " + arquivos.size());
            for (Path arquivo : arquivos) {
                parseArquivoDespesas(arquivo, consumer);
            }
        }
    }

    private static void escreverEvento(SequenceWriter writer, DespesaEvento evento) {
        Map<String, Object> linha = new LinkedHashMap<>();
        linha.put("CNPJ", evento.getCnpj());
        linha.put("RegistroANS", evento.getRegistroAns());
        linha.put("Ano", evento.getAno());
        linha.put("Trimestre", evento.getTrimestre());
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
    private static String buildDedupeKey(DespesaEvento evento) {
        return evento.getCnpj() + "|" + evento.getAno() + "|" + evento.getTrimestre();
    }

    private static void gerarConsolidado(List<TrimestresFinder.TrimestreInfo> trimestres,
                                         Path baseDir,
                                         Path csvSaida) throws Exception {
        CsvMapper mapper = new CsvMapper();
        CsvSchema.Builder schemaBuilder = CsvSchema.builder();
        for (String coluna : COLUNAS_SAIDA) {
            schemaBuilder.addColumn(coluna);
        }
        CsvSchema schema = schemaBuilder.setUseHeader(true)
                .setColumnSeparator(';')
                .setQuoteChar('"')
                .build();

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
            System.out.println("Arquivos encontrados em " + info.trimestre() + ": " + arquivos.size());
            for (Path arquivo : arquivos) {
                parseArquivoDespesas(arquivo, consumer);
            }
        }
    }

    private static void escreverEvento(SequenceWriter writer, DespesaEvento evento) {
        Map<String, Object> linha = new LinkedHashMap<>();
        linha.put("RegistroANS", evento.getRegistroAns());
        linha.put("Ano", evento.getAno());
        linha.put("Trimestre", evento.getTrimestre());
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
