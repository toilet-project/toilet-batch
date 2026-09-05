package com.example.toiletbatch.region;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.example.toiletbatch.region.RegionModel.*;

/** Keyset scanning + source fingerprints gives restartability without an unsafe last-id-only cursor. */
public final class RegionJob {
    private final RegionRepository repository;
    private final RegionNormalizer normalizer;
    private final RegionJournal journal;
    private final Clock clock;
    public RegionJob(RegionRepository repository, RegionNormalizer normalizer, RegionJournal journal, Clock clock) {
        this.repository = repository; this.normalizer = normalizer; this.journal = journal; this.clock = clock;
    }
    public Map<String, Object> run(boolean apply, boolean fillMissing, int maxItems) throws Exception {
        if (maxItems < 1 || (fillMissing && !apply)) throw new IllegalArgumentException("Invalid region job options");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", apply ? "APPLY" : "DRY_RUN"); report.put("totalToilets", repository.count());
        Map<String, Long> counts = new LinkedHashMap<>(); report.put("counts", counts);
        for (String key : new String[]{"scanned", "checkpointSkipped", "processed", "reverseSuccess", "missingCoordinates",
                "fallbackSuccess", "manualReview", "retryPending", "recheckVerified", "concurrentChangeSkipped", "coordinatesFilled", "candidateOnly"}) counts.put(key, 0L);
        for (Status status : Status.values()) counts.put(status.name(), 0L);
        long after = 0; int work = 0;
        try (AutoCloseable ignored = apply ? repository.lock() : () -> { }) {
            outer: while (true) {
                var page = apply ? repository.pageWithRegions(after, 100) : repository.page(after, 100).stream()
                        .map(s -> new RegionRepository.Snapshot(s, journal.get(s.toiletId()))).toList();
                if (page.isEmpty()) { report.put("scanComplete", true); break; }
                for (var snapshot : page) {
                    Source source = snapshot.source();
                    after = source.toiletId(); increment(counts, "scanned");
                    Result stored = snapshot.result();
                    if (fresh(stored, source, clock.millis())) { increment(counts, "checkpointSkipped"); continue; }
                    Result cached = journal.get(source.toiletId());
                    Result result;
                    if (source.missing() && !fillMissing) {
                        // Missing source coordinates await an administrator; do not spend API quota on candidates.
                        boolean partial = source.latitude() != null || source.longitude() != null;
                        result = new Result(source, source.point(), null, partial ? Status.INVALID_COORDINATE : Status.NO_COORDINATE,
                                partial ? "PARTIAL_COORDINATE" : "ADMIN_COORDINATE_REQUIRED", Check.UNKNOWN, Check.UNKNOWN,
                                "NONE", clock.millis(), VERSION);
                    } else result = fresh(cached, source, clock.millis()) ? cached : normalizer.normalize(source);
                    // Checkpoint before DB write; crash before/after commit is safe because apply checks DB state too.
                    journal.record(result);
                    increment(counts, "processed");
                    increment(counts, result.status().name());
                    if (result.region() != null) increment(counts, "reverseSuccess");
                    if (source.missing()) increment(counts, "missingCoordinates");
                    if (!"NONE".equals(result.fallback()) && result.evaluated() != null && result.evaluated().valid()) increment(counts, "fallbackSuccess");
                    if (result.retryable()) increment(counts, "retryPending");
                    else if (result.status() != Status.VERIFIED) increment(counts, "manualReview");
                    if (result.status() == Status.VERIFIED && result.evidence() != null) increment(counts, "recheckVerified");
                    if (source.missing() && result.status() == Status.VERIFIED && (!apply || !fillMissing)) increment(counts, "candidateOnly");
                    if (apply) {
                        var applied = repository.apply(result, fillMissing);
                        if (applied.conflict()) increment(counts, "concurrentChangeSkipped");
                        if (applied.coordinatesFilled()) increment(counts, "coordinatesFilled");
                    }
                    if (++work >= maxItems) { report.put("scanComplete", false); break outer; }
                }
            }
        } catch (RegionProvider.Stop e) {
            report.put("stopped", e.getMessage()); report.put("scanComplete", false);
        }
        report.put("lastScannedId", after); report.put("callsTodayInJournal", journal.callsToday(clock));
        return report;
    }
    public static boolean fresh(Result result, Source source, long now) {
        if (result == null || !VERSION.equals(result.algorithmVersion()) || !result.source().hash().equals(source.hash())) return false;
        long ttl = result.retryable() ? 86400000L : 30L * 86400000;
        return now >= result.checkedEpochMillis() && now - result.checkedEpochMillis() < ttl;
    }
    private static void increment(Map<String, Long> counts, String key) { counts.merge(key, 1L, Long::sum); }
}
