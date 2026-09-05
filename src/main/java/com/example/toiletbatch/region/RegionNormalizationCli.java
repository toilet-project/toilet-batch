package com.example.toiletbatch.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Standalone main: no Spring context, web server, Flyway, Hibernate or 02:00 sync can start. */
public final class RegionNormalizationCli {
    public static void main(String[] args) throws Exception {
        boolean apply = Arrays.asList(args).contains("--apply");
        boolean fill = Arrays.asList(args).contains("--fill-missing");
        for (String arg : args) if (!arg.equals("--apply") && !arg.equals("--fill-missing")) throw new IllegalArgumentException("Unknown option");
        String dbUrl = required("SPRING_DB_URL");
        var source = new DriverManagerDataSource(dbUrl, required("SPRING_DB_USERNAME"), required("SPRING_DB_PASSWORD"));
        String sample = System.getenv("REGION_SAMPLE_IDS");
        if (apply && sample != null && !sample.isBlank()) throw new IllegalArgumentException("Sample selection is dry-run only");
        var ids = sample == null || sample.isBlank() ? java.util.List.<Long>of() : Arrays.stream(sample.split(",")).map(String::strip).map(Long::valueOf).toList();
        Map<String, Object> report = execute(new RegionRepository(source, ids), apply, fill);
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (report.containsKey("stopped")) System.exit(2);
    }
    public static Map<String, Object> execute(RegionRepository repository, boolean apply, boolean fill) throws Exception {
        Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));
        Path path = Path.of(required("REGION_JOURNAL_PATH"));
        try (var journal = new RegionJournal(path)) {
            var provider = new KakaoRegionProvider(required("KAKAO_REST_API_KEY"), journal, clock,
                    integer("REGION_DAILY_CALL_BUDGET", 1000), integer("REGION_DELAY_MS", 1000));
            var normalizer = new RegionNormalizer(provider, clock);
            return new RegionJob(repository, normalizer, journal, clock).run(apply, fill, integer("REGION_MAX_ITEMS", 100));
        }
    }
    private static int integer(String name, int fallback) { String value = System.getenv(name); return value == null ? fallback : Integer.parseInt(value); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
