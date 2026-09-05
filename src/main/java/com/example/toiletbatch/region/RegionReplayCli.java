package com.example.toiletbatch.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static com.example.toiletbatch.region.RegionModel.*;

/** Imports a reviewed analysis snapshot without constructing any HTTP provider. Dry-run by default. */
public final class RegionReplayCli {
    public static void main(String[] args) throws Exception {
        for (String arg : args) if (!arg.equals("--apply")) throw new IllegalArgumentException("Only --apply is supported");
        Path input = Path.of(required("REGION_REPLAY_PATH"));
        String expected = required("REGION_REPLAY_SHA256");
        Map<Long, Result> results = read(input, expected);
        var ds = new DriverManagerDataSource(required("SPRING_DB_URL"), required("SPRING_DB_USERNAME"), required("SPRING_DB_PASSWORD"));
        String max = System.getenv("REGION_MAX_ITEMS");
        var report = new RegionReplayJob(new RegionRepository(ds), results, Clock.systemUTC())
                .run(Arrays.asList(args).contains("--apply"), max == null ? 100 : Integer.parseInt(max));
        report.put("inputSha256", expected);
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    /** Strict, read-only loading: corrupt/truncated files must fail before opening the database. */
    public static Map<Long, Result> read(Path input, String expected) throws Exception {
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected lowercase SHA256");
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        Map<Long, Result> results = new LinkedHashMap<>();
        var json = new ObjectMapper();
        try (var stream = new java.security.DigestInputStream(Files.newInputStream(input), digest);
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var node = json.readTree(line);
                if (node == null || !node.has("kind")) throw new IllegalArgumentException("Invalid journal entry");
                switch (node.path("kind").asText()) {
                    case "result" -> {
                        Result next = json.treeToValue(node.get("value"), Result.class);
                        if (next == null || next.source() == null || next.source().toiletId() <= 0 || next.status() == null)
                            throw new IllegalArgumentException("Invalid result");
                        results.merge(next.source().toiletId(), next,
                                (prior, value) -> value.checkedEpochMillis() >= prior.checkedEpochMillis() ? value : prior);
                    }
                    case "reverse", "call" -> { /* Only result entries are imported. */ }
                    default -> throw new IllegalArgumentException("Unknown journal entry");
                }
            }
        }
        if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IllegalArgumentException("Analysis checksum mismatch");
        if (results.isEmpty()) throw new IllegalArgumentException("No results to import");
        return Map.copyOf(results);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
