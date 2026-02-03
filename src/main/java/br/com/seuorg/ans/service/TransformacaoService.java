package br.com.seuorg.ans.service;

import br.com.seuorg.ans.OperadoraCadastro;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TransformacaoService {

    private static final Logger LOGGER = Logger.getLogger(TransformacaoService.class.getName());
    private static final String OPERADORAS_ATIVAS_URL =
            "https://dadosabertos.ans.gov.br/FTP/PDA/operadoras_de_plano_de_saude_ativas/operadoras_de_plano_de_saude_ativas.csv";

    private final ValidadorService validador = new ValidadorService();

    public void gerarDespesasAgregadas(Path baseDir, Path consolidado, Path saida) throws Exception {
        Path cadastro = ensureCadastroOperadoras(baseDir);
        Map<String, OperadoraCadastro> cadastroPorCnpj = carregarCadastroOperadoras(cadastro);
        List<DespesaEnriquecida> enriquecidas = carregarDespesas(consolidado, cadastroPorCnpj);

        Map<ChaveGrupo, Estatistica> estatisticas = enriquecidas.stream()
                .collect(Collectors.groupingBy(
                        e -> new ChaveGrupo(e.razaoSocial(), e.uf()),
                        Collectors.collectingAndThen(Collectors.toList(), Estatistica::from)
                ));

        List<ResultadoAgregado> resultados = estatisticas.entrySet().stream()
                .map(entry -> ResultadoAgregado.of(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ResultadoAgregado::total).reversed())
                .toList();

        escreverAgregado(saida, resultados);
    }

    private List<DespesaEnriquecida> carregarDespesas(Path consolidado,
                                                      Map<String, OperadoraCadastro> cadastroPorCnpj) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(';')
                .setQuote('"')
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        List<DespesaEnriquecida> saida = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(consolidado, detectCharset(consolidado));
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                String cnpj = normalizeCnpj(record.get("CNPJ"));
                String valorStr = record.get("ValorDespesas");
                BigDecimal valor = parseBigDecimal(valorStr);
                if (!validador.validarOuLogar(cnpj, null, valor)) {
                    continue;
                }

                OperadoraCadastro cadastro = cadastroPorCnpj.get(cnpj);
                String razao = cadastro == null ? "" : cadastro.getRazaoSocial();
                String uf = cadastro == null ? "" : cadastro.getUf();
                String registro = cadastro == null ? "" : cadastro.getRegistroAns();
                String modalidade = cadastro == null ? "" : cadastro.getModalidade();

                if (cadastro != null && !validador.isRazaoSocialValida(razao)) {
                    LOGGER.log(Level.WARNING, "Registro descartado por razao social vazia. CNPJ={0}", cnpj);
                    continue;
                }

                saida.add(new DespesaEnriquecida(
                        cnpj,
                        razao,
                        uf,
                        registro,
                        modalidade,
                        valor
                ));
            }
        }
        return saida;
    }

    private void escreverAgregado(Path saida, List<ResultadoAgregado> resultados) throws IOException {
        CsvMapper mapper = new CsvMapper();
        CsvSchema schema = CsvSchema.builder()
                .addColumn("RazaoSocial")
                .addColumn("UF")
                .addColumn("Total")
                .addColumn("Media")
                .addColumn("DesvioPadrao")
                .setUseHeader(true)
                .setColumnSeparator(';')
                .setQuoteChar('"')
                .build();

        try (Writer writer = Files.newBufferedWriter(saida, StandardCharsets.UTF_8);
             SequenceWriter sequenceWriter = mapper.writer(schema).writeValues(writer)) {
            for (ResultadoAgregado resultado : resultados) {
                Map<String, Object> linha = new LinkedHashMap<>();
                linha.put("RazaoSocial", resultado.razaoSocial());
                linha.put("UF", resultado.uf());
                linha.put("Total", resultado.total().toPlainString());
                linha.put("Media", resultado.media().toPlainString());
                linha.put("DesvioPadrao", String.format(Locale.ROOT, "%.2f", resultado.desvioPadrao()));
                sequenceWriter.write(linha);
            }
        }
    }

    private Path ensureCadastroOperadoras(Path baseDir) throws Exception {
        Path cadastro = baseDir.resolve("operadoras_de_plano_de_saude_ativas.csv");
        if (Files.exists(cadastro)) {
            LOGGER.log(Level.INFO, "Cadastro de operadoras encontrado em cache: {0}", cadastro.getFileName());
            return cadastro;
        }
        download(OPERADORAS_ATIVAS_URL, cadastro);
        return cadastro;
    }

    private void download(String url, Path dest) throws Exception {
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

    private Map<String, OperadoraCadastro> carregarCadastroOperadoras(Path arquivo) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(';')
                .setQuote('"')
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        Map<String, OperadoraCadastro> porCnpj = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(arquivo, detectCharset(arquivo));
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                String cnpj = normalizeCnpj(record.get("CNPJ"));
                if (cnpj == null) {
                    continue;
                }
                String registro = normalizeValue(record.get("Registro_ANS"));
                String modalidade = normalizeValue(record.get("Modalidade"));
                String uf = normalizeValue(record.get("UF"));
                String razao = normalizeValue(record.get("Razao_Social"));
                OperadoraCadastro cadastro = new OperadoraCadastro(cnpj, registro, modalidade, uf, razao);
                OperadoraCadastro existente = porCnpj.putIfAbsent(cnpj, cadastro);
                if (existente != null && !Objects.equals(existente.getRazaoSocial(), razao)) {
                    LOGGER.log(Level.WARNING, "CNPJ duplicado com razao social diferente: {0}", cnpj);
                }
            }
        }
        return porCnpj;
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
            decoder.decode(ByteBuffer.wrap(sample));
            return StandardCharsets.UTF_8;
        } catch (Exception ignored) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private record DespesaEnriquecida(
            String cnpj,
            String razaoSocial,
            String uf,
            String registroAns,
            String modalidade,
            BigDecimal valor
    ) {}

    private record ChaveGrupo(String razaoSocial, String uf) {}

    private static final class Estatistica {
        private BigDecimal total = BigDecimal.ZERO;
        private long count = 0;
        private double sumSquares = 0.0;

        private static Estatistica from(List<DespesaEnriquecida> dados) {
            Estatistica estatistica = new Estatistica();
            for (DespesaEnriquecida d : dados) {
                estatistica.add(d.valor());
            }
            return estatistica;
        }

        private void add(BigDecimal valor) {
            if (valor == null) {
                return;
            }
            total = total.add(valor);
            count++;
            double v = valor.doubleValue();
            sumSquares += v * v;
        }

        private BigDecimal total() {
            return total;
        }

        private BigDecimal media() {
            if (count == 0) {
                return BigDecimal.ZERO;
            }
            return total.divide(BigDecimal.valueOf(count), 6, java.math.RoundingMode.HALF_UP);
        }

        private double desvioPadrao() {
            if (count == 0) {
                return 0.0;
            }
            double mean = total.doubleValue() / count;
            double variance = (sumSquares / count) - (mean * mean);
            return Math.sqrt(Math.max(variance, 0.0));
        }
    }

    private record ResultadoAgregado(String razaoSocial, String uf, BigDecimal total, BigDecimal media, double desvioPadrao) {
        private static ResultadoAgregado of(ChaveGrupo chave, Estatistica estatistica) {
            return new ResultadoAgregado(
                    chave.razaoSocial(),
                    chave.uf(),
                    estatistica.total(),
                    estatistica.media(),
                    estatistica.desvioPadrao()
            );
        }
    }
}
