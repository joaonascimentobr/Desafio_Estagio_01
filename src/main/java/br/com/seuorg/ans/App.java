package br.com.seuorg.ans;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.net.URI;
import java.net.http.*;
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

        Path baseDir = Paths.get("target", "ans");
        Files.createDirectories(baseDir);

        Path zip = baseDir.resolve("1T2025.zip");
        Path extractDir = baseDir.resolve("1T2025");

        System.out.println("Baixando ZIP...");
        download(ZIP_URL, zip);

        System.out.println("Extraindo ZIP...");
        unzip(zip, extractDir);

        Path csv = findCsv(extractDir);
        System.out.println("CSV encontrado: " + csv);

        System.out.println("Lendo CSV...");
        List<Trimestres> lista = parseCsv(csv);

        System.out.println("Total de registros: " + lista.size());
        lista.stream().limit(3).forEach(t ->
                System.out.println(t.getData() + " | " + t.getVlSaldoFinal())
        );
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

    private static Path findCsv(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".csv"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static List<Trimestres> parseCsv(Path csv) throws IOException {

        CsvMapper mapper = new CsvMapper();
        mapper.registerModule(new JavaTimeModule());

        CsvSchema schema = CsvSchema.emptySchema()
                .withHeader()
                .withColumnSeparator(';');

        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            MappingIterator<Trimestres> it =
                    mapper.readerFor(Trimestres.class)
                            .with(schema)
                            .readValues(reader);

            return it.readAll();
        }
    }
}
