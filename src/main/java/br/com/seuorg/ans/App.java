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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class App {

    private static final String FRASE_DESPESAS = "Despesas com Eventos/Sinistros";
    private static final Pattern EVENTOS_SINISTROS_PATTERN = Pattern.compile("\\b(eventos|sinistros)\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> CONTAS_EVENTOS_SINISTROS_PREFIXOS = List.of("3.04.01.04");
    private static final Map<String, List<String>> COLUNAS_ALIAS = Map.of(
            "RegistroANS", List.of("REG_ANS", "REGANS", "REGISTRO ANS"),
            "CNPJ", List.of("CNPJ", "CNPJ_PRESTADOR", "CNPJ_PRESTADORA"),
            "RazaoSocial", List.of("RAZAO_SOCIAL", "RAZÃO_SOCIAL", "RAZAO SOCIAL", "RAZÃO SOCIAL", "NOME_EMPRESA", "PRESTADOR", "PRESTADORA"),
            "Data", List.of("DATA", "DT_REF", "DATA_REFERENCIA", "DATA REFERENCIA"),
            "Trimestre", List.of("TRIMESTRE", "TRIM", "COMPETENCIA", "COMPETÊNCIA", "PERIODO", "PERÍODO"),
            "Ano", List.of("ANO", "ANO_REF", "ANO_REFERENCIA", "ANO REFERENCIA"),
            "ContaContabil", List.of("CD_CONTA_CONTABIL", "CONTA_CONTABIL", "CONTA CONTABIL"),
            "Descricao", List.of("DESCRICAO", "DESCR", "DESCRIÇÃO"),
            "ValorDespesas", List.of("VL_SALDO_FINAL", "VL_SALDO", "VALOR", "VALOR DESPESA", "VL_DESPESA")
    );
    private static final List<String> COLUNAS_SAIDA = List.of(
            "RegistroANS",
            "CNPJ",
            "RazaoSocial",
            "Ano",
            "Trimestre",
            "TrimestreReferencia",
            "ValorDespesas",
            "StatusValidacao"
    );
    private static final List<DateTimeFormatter> FORMATOS_DATA = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu")
    );
    private static final Pattern TRIMESTRE_PADRAO = Pattern.compile("(?i)\\b([1-4])\\s*T\\s*(20\\d{2})\\b");
    private static final Pattern TRIMESTRE_ANO_PRIMEIRO = Pattern.compile("(?i)\\b(20\\d{2})\\s*[-_./ ]*([1-4])\\s*(?:T|TRIMESTRE|TRIM)?\\b");
    private static final Pattern TRIMESTRE_TEXTO = Pattern.compile("(?i)\\b([1-4])\\s*[-_./ ]*\\s*(?:T|TRIMESTRE|TRIM)?\\s*[-_./ ]*\\s*(20\\d{2})\\b");

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

        Path relatorioValidacao = baseDir.resolve("relatorio_validacao.csv");

        System.out.println("Gerando consolidado em " + csvSaida + "...");
        gerarConsolidado(recentes, baseDir, csvSaida, relatorioValidacao);
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
        CsvMapper mapper = new CsvMapper();
        char separador = detectarSeparador(arquivo);
        CsvSchema schema = CsvSchema.emptySchema()
                .withHeader()
                .withColumnSeparator(separador)
                .withQuoteChar('"');

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
        String registroAns = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("RegistroANS"));
        String cnpj = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("CNPJ"));
        String razaoSocial = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("RazaoSocial"));
        String dataStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Data"));
        String trimestreStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Trimestre"));
        String anoStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Ano"));
        String contaContabil = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("ContaContabil"));
        String descricao = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("Descricao"));
        String valorStr = getValueByAliases(row, headerLookup, COLUNAS_ALIAS.get("ValorDespesas"));
        if (registroAns == null && dataStr == null && contaContabil == null && descricao == null && valorStr == null
                && trimestreStr == null && anoStr == null && cnpj == null && razaoSocial == null) {
            return null;
        }
        if (!isDespesaEvento(contaContabil, descricao)) {
            return null;
        }
        LocalDate dataReferencia = parseData(dataStr);
        DespesaEvento evento = new DespesaEvento();
        evento.setRegistroAns(normalizeValue(registroAns));
        evento.setCnpj(normalizeCnpj(cnpj));
        evento.setRazaoSocial(normalizeRazaoSocial(razaoSocial));
        if (dataReferencia != null) {
            evento.setAno(dataReferencia.getYear());
            evento.setTrimestre(trimestreFromMonth(dataReferencia.getMonthValue()));
        } else {
            TrimestreInfo trimestreInfo = parseTrimestreInfo(trimestreStr, anoStr);
            if (trimestreInfo == null) {
                return null;
            }
            evento.setAno(trimestreInfo.ano());
            evento.setTrimestre(trimestreInfo.trimestre());
        }
        evento.setTrimestreReferencia(normalizeTrimestreReferencia(evento.getTrimestre(), evento.getAno()));
        evento.setValorDespesas(parseBigDecimal(normalizeValue(valorStr)));
        if (evento.getRegistroAns() == null || evento.getValorDespesas() == null) {
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

    private static String trimestreFromMonth(int month) {
        int trimestre = (month - 1) / 3 + 1;
        return trimestre + "T";
    }

    private static TrimestreInfo parseTrimestreInfo(String trimestreRaw, String anoRaw) {
        String trimestreNormalized = normalizeValue(trimestreRaw);
        String anoNormalized = normalizeValue(anoRaw);
        if (trimestreNormalized != null) {
            TrimestreInfo info = parseTrimestreFromString(trimestreNormalized);
            if (info != null) {
                return info;
            }
            Integer quarterOnly = parseQuarterOnly(trimestreNormalized);
            Integer yearOnly = parseYearOnly(anoNormalized);
            if (quarterOnly != null && yearOnly != null) {
                return new TrimestreInfo(quarterOnly + "T", yearOnly);
            }
        }
        return null;
    }

    private static TrimestreInfo parseTrimestreFromString(String value) {
        Matcher matcher = TRIMESTRE_PADRAO.matcher(value);
        if (matcher.find()) {
            return new TrimestreInfo(matcher.group(1) + "T", Integer.parseInt(matcher.group(2)));
        }
        matcher = TRIMESTRE_ANO_PRIMEIRO.matcher(value);
        if (matcher.find()) {
            return new TrimestreInfo(matcher.group(2) + "T", Integer.parseInt(matcher.group(1)));
        }
        matcher = TRIMESTRE_TEXTO.matcher(value);
        if (matcher.find()) {
            return new TrimestreInfo(matcher.group(1) + "T", Integer.parseInt(matcher.group(2)));
        }
        return null;
    }

    private static Integer parseQuarterOnly(String value) {
        String trimmed = normalizeValue(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.matches("[1-4]")) {
            return Integer.parseInt(trimmed);
        }
        return null;
    }

    private static Integer parseYearOnly(String value) {
        String trimmed = normalizeValue(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.matches("20\\d{2}")) {
            return Integer.parseInt(trimmed);
        }
        return null;
    }

    private static String normalizeTrimestreReferencia(String trimestre, Integer ano) {
        if (trimestre == null || ano == null) {
            return null;
        }
        TrimestreInfo info = parseTrimestreFromString(trimestre);
        if (info != null) {
            return info.trimestre() + info.ano();
        }
        Matcher trimestreMatcher = Pattern.compile("(?i)\\b([1-4])\\s*T\\b").matcher(trimestre);
        if (trimestreMatcher.find()) {
            return trimestreMatcher.group(1) + "T" + ano;
        }
        return trimestre + ano;
    }

    private static String normalizeCnpj(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    private static String normalizeRazaoSocial(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
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
                                         Path csvSaida,
                                         Path relatorioValidacao) throws Exception {
        List<DespesaEvento> eventos = new ArrayList<>();
        for (TrimestresFinder.TrimestreInfo info : trimestres) {
            processarTrimestre(info, baseDir, eventos::add);
        }
        Set<String> cnpjsComRazoesDivergentes = identificarCnpjsComRazoesDivergentes(eventos);
        aplicarValidacoes(eventos, cnpjsComRazoesDivergentes);
        gerarRelatorioValidacao(relatorioValidacao, cnpjsComRazoesDivergentes, eventos);

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
            for (DespesaEvento evento : eventos) {
                escreverEvento(sequenceWriter, evento);
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
        linha.put("CNPJ", evento.getCnpj());
        linha.put("RazaoSocial", evento.getRazaoSocial());
        linha.put("Ano", evento.getAno());
        linha.put("Trimestre", evento.getTrimestre());
        linha.put("TrimestreReferencia", evento.getTrimestreReferencia());
        linha.put("ValorDespesas", evento.getValorDespesas() == null ? null : evento.getValorDespesas().toPlainString());
        linha.put("StatusValidacao", evento.getStatusValidacao());
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

    private record TrimestreInfo(String trimestre, Integer ano) {}

    private static Set<String> identificarCnpjsComRazoesDivergentes(List<DespesaEvento> eventos) {
        Map<String, Set<String>> cnpjRazoes = new HashMap<>();
        for (DespesaEvento evento : eventos) {
            String cnpj = evento.getCnpj();
            String razao = evento.getRazaoSocial();
            if (cnpj == null || razao == null) {
                continue;
            }
            cnpjRazoes.computeIfAbsent(cnpj, key -> new HashSet<>()).add(razao);
        }
        Set<String> divergentes = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : cnpjRazoes.entrySet()) {
            if (entry.getValue().size() > 1) {
                divergentes.add(entry.getKey());
            }
        }
        return divergentes;
    }

    private static void aplicarValidacoes(List<DespesaEvento> eventos, Set<String> cnpjsDivergentes) {
        for (DespesaEvento evento : eventos) {
            List<String> flags = new ArrayList<>();
            BigDecimal valor = evento.getValorDespesas();
            if (valor != null && valor.compareTo(BigDecimal.ZERO) <= 0) {
                flags.add("VALOR_SUSPEITO");
            }
            if (evento.getCnpj() != null && cnpjsDivergentes.contains(evento.getCnpj())) {
                flags.add("CNPJ_DIVERGENTE");
            }
            if (flags.isEmpty()) {
                evento.setStatusValidacao("OK");
            } else {
                evento.setStatusValidacao(String.join("|", flags));
            }
        }
    }

    private static void gerarRelatorioValidacao(Path relatorioValidacao,
                                                Set<String> cnpjsDivergentes,
                                                List<DespesaEvento> eventos) throws IOException {
        if (cnpjsDivergentes.isEmpty()) {
            Files.deleteIfExists(relatorioValidacao);
            return;
        }
        Map<String, Set<String>> razoesPorCnpj = new HashMap<>();
        for (DespesaEvento evento : eventos) {
            String cnpj = evento.getCnpj();
            String razao = evento.getRazaoSocial();
            if (cnpj == null || razao == null) {
                continue;
            }
            if (cnpjsDivergentes.contains(cnpj)) {
                razoesPorCnpj.computeIfAbsent(cnpj, key -> new HashSet<>()).add(razao);
            }
        }
        CsvMapper mapper = new CsvMapper();
        CsvSchema schema = CsvSchema.builder()
                .addColumn("CNPJ")
                .addColumn("RazoesSociais")
                .addColumn("Observacao")
                .setUseHeader(true)
                .setColumnSeparator(';')
                .build();
        try (Writer writer = Files.newBufferedWriter(relatorioValidacao, StandardCharsets.UTF_8);
             SequenceWriter sequenceWriter = mapper.writer(schema).writeValues(writer)) {
            for (Map.Entry<String, Set<String>> entry : razoesPorCnpj.entrySet()) {
                Map<String, Object> linha = new LinkedHashMap<>();
                linha.put("CNPJ", entry.getKey());
                linha.put("RazoesSociais", String.join(" | ", entry.getValue()));
                linha.put("Observacao", "CNPJ com razoes sociais divergentes");
                sequenceWriter.write(linha);
            }
        }
    }
}
