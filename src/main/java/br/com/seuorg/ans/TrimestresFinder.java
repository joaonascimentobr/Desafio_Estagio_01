package br.com.seuorg.ans;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrimestresFinder {

    private static final String BASE_URL =
            "https://dadosabertos.ans.gov.br/FTP/PDA/demonstracoes_contabeis/";

    private static final Pattern HREF_PATTERN = Pattern.compile("href=\"([^\"]+)\"");

    private final HttpClient client;

    public TrimestresFinder() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build());
    }

    public TrimestresFinder(HttpClient client) {
        this.client = client;
    }

    public List<TrimestreInfo> findRecentTrimestres(int count) throws IOException, InterruptedException {
        List<String> candidates = generateCandidates(count, 6);
        Map<Integer, List<URI>> yearCache = new HashMap<>();
        List<TrimestreInfo> resultados = new ArrayList<>();

        for (String trimestre : candidates) {
            int year = Integer.parseInt(trimestre.substring(2));
            List<URI> zips = yearCache.computeIfAbsent(year, this::scanYear);
            List<URI> matches = new ArrayList<>();

            for (URI zip : zips) {
                if (zip.toString().contains(trimestre) && urlExists(zip)) {
                    matches.add(zip);
                }
            }

            if (!matches.isEmpty()) {
                resultados.add(new TrimestreInfo(trimestre, matches));
                if (resultados.size() >= count) {
                    break;
                }
            }
        }

        return resultados;
    }

    private List<String> generateCandidates(int count, int yearsBack) {
        List<String> candidates = new ArrayList<>();
        LocalDate now = LocalDate.now();
        int currentYear = now.getYear();
        int currentQuarter = (now.getMonthValue() - 1) / 3 + 1;

        for (int year = currentYear; year >= currentYear - yearsBack; year--) {
            int startQuarter = (year == currentYear) ? currentQuarter : 4;
            for (int quarter = startQuarter; quarter >= 1; quarter--) {
                candidates.add(quarter + "T" + year);
                if (candidates.size() >= count * 6) {
                    return candidates;
                }
            }
        }
        return candidates;
    }

    private List<URI> scanYear(int year) {
        URI base = URI.create(BASE_URL + year + "/");
        return collectZipUrls(base);
    }

    private List<URI> collectZipUrls(URI base) {
        List<URI> zips = new ArrayList<>();
        Queue<URI> toVisit = new ArrayDeque<>();
        Set<URI> visited = new HashSet<>();

        toVisit.add(base);

        while (!toVisit.isEmpty()) {
            URI current = toVisit.poll();
            if (!visited.add(current)) {
                continue;
            }

            String body;
            try {
                body = fetchText(current);
            } catch (IOException | InterruptedException ex) {
                continue;
            }

            Matcher matcher = HREF_PATTERN.matcher(body);
            while (matcher.find()) {
                String href = matcher.group(1);
                if (href.startsWith("../") || href.startsWith("?") || href.isBlank()) {
                    continue;
                }
                URI resolved = current.resolve(href);
                String lower = href.toLowerCase(Locale.ROOT);

                if (lower.endsWith("/")) {
                    if (resolved.toString().startsWith(base.toString())) {
                        toVisit.add(resolved);
                    }
                } else if (lower.endsWith(".zip")) {
                    zips.add(resolved);
                }
            }
        }

        return zips;
    }

    private String fetchText(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    private boolean urlExists(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 400) {
                return true;
            }
            if (status != 405 && status != 403) {
                return false;
            }
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .build();
            HttpResponse<Void> getResponse =
                    client.send(getRequest, HttpResponse.BodyHandlers.discarding());
            int getStatus = getResponse.statusCode();
            return getStatus >= 200 && getStatus < 400;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }

    public record TrimestreInfo(String trimestre, List<URI> urls) {}
}
