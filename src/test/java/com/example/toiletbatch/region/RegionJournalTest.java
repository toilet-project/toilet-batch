package com.example.toiletbatch.region;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;

class RegionJournalTest {
    @TempDir Path directory;
    Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    @Test void resumesResultsCacheAndCallBudgetAndRecoversTornTail() throws Exception {
        Path path = directory.resolve("results.jsonl");
        Point p = new Point(new BigDecimal("36.3"), new BigDecimal("127.3"));
        Region region = new Region("대전광역시", "30", "유성구", "30200", null, null, "3020012200", null);
        Source source = new Source(42, "대전 유성구", null, p.latitude(), p.longitude());
        Result result = new Result(source, p, region, Status.VERIFIED, "OK", Check.MATCH, Check.UNKNOWN, "NONE", clock.millis(), VERSION);
        try (var journal = new RegionJournal(path)) {
            journal.record(result); journal.cache(p, region, clock.millis()); journal.reserveCall(clock, 1);
            assertThrows(Exception.class, () -> new RegionJournal(path));
        }
        Files.writeString(path, "{\"kind\":", StandardOpenOption.APPEND);
        try (var journal = new RegionJournal(path)) {
            assertEquals(result, journal.get(42)); assertEquals(region, journal.cached(p, clock.millis()));
            assertThrows(RegionProvider.Stop.class, () -> journal.reserveCall(clock, 1));
            assertTrue(RegionJob.fresh(result, source, clock.millis()));
            assertFalse(RegionJob.fresh(result, source.withPoint(new Point(BigDecimal.ONE, BigDecimal.TEN)), clock.millis()));
            assertFalse(RegionJob.fresh(result, new Source(42, "대전 유성구 오타 수정", null, p.latitude(), p.longitude()), clock.millis()));
            assertFalse(RegionJob.fresh(result, source, clock.millis() + 31L * 86400000));
        }
    }
    @Test void sourceHashDistinguishesNullAndEmptyButNotDecimalScale() {
        Source a = new Source(1, "", null, new BigDecimal("36.3"), new BigDecimal("127.3"));
        Source b = new Source(1, "", null, new BigDecimal("36.3000000"), new BigDecimal("127.3000000"));
        assertEquals(a.hash(), b.hash());
        assertNotEquals(a.hash(), new Source(1, null, "", a.latitude(), a.longitude()).hash());
    }
}
