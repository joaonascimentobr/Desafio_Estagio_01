package br.com.seuorg.ans;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class App {

    private static final String ZIP_URL =
            "https://dadosabertos.ans.gov.br/FTP/PDA/demonstracoes_contabeis/2025/1T2025.zip";

    public static void main(String[] args) throws Exception {
        Path workDir = Paths.get("target", "ans");
        Files.createDirectories(workDir);

        Path zipPath = workDir.resolve("1T2025.zip");
        Path extractDir = workDir.resolve("1T2025");

        System.out.println("Baixando ZIP...");
        downloadToFile(ZIP_URL, zipPath);

        System.out.println("Extraindo ZIP...");
        unzip(zipPath, extractDir);

        Path csv = findFirstCsv(extractDir)
                .orElseThrow(() -> new IllegalStateException("Nenhum .csv encontrado em " + extractDir));

        System.out.println("CSV encontrado: " + csv);

        System.out.println("Lendo CSV e mapeando para Trimestres...");
        List<Trimestres> registros = parseCsvToTrimestres(csv);

        System.out.println("Total de registros: " + registros.size());

        // Exemplo: imprime os 3 primeiros
        registros.stream().limit(3).forEach(r ->
                System.out.println(r.getData() + " | " + r.getRegAns() + " | " + r.getCdContaContabil() + " | " + r.getDescricao())
        );
    }

    private static void downloadToFile(String url, Path dest) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Falha no download. HTTP " + response.statusCode());
        }

        Files.createDirectories(dest.getParent());
        try (InputStream in = response.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void unzip(Path zipFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(entry.getName()).normalize();

                // Proteção contra Zip Slip
                if (!outPath.startsWith(destDir)) {
                    throw new IOException("Entrada maliciosa no zip: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream out = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static Optional<Path> findFirstCsv(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .findFirst();
        }
    }

    private static List<Trimestres> parseCsvToTrimestres(Path csvPath) throws IOException {
        CsvMapper mapper = new CsvMapper();

        // Ajuste se necessário (separador ;, encoding etc.)
        CsvSchema schema = CsvSchema.emptySchema()
                .withHeader()
                .withColumnSeparator(';'); // se for vírgula, troque para ','

        // Se o CSV vier em ISO-8859-1, troque o charset para ISO_8859_1
        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            MappingIterator<Trimestres> it = mapper
                    .readerFor(Trimestres.class)
                    .with(schema)
                    .readValues(reader);

            return it.readAll();
        }
    }
}
