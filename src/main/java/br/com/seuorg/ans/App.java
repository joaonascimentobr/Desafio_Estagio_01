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

        TrimestresFinder.TrimestreInfo maisRecente = recentes.get(0);
        URI zipUrl = maisRecente.urls().getFirst();

        String zipName = Paths.get(zipUrl.getPath()).getFileName().toString();
        Path zip = baseDir.resolve(zipName);
        Path extractDir = baseDir.resolve(maisRecente.trimestre());

        System.out.println("Baixando ZIP...");
        download(zipUrl.toString(), zip);

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
