package com.example.toiletbatch.region;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.example.toiletbatch.region.RegionModel.*;

/** Source fingerprints + short row transactions protect an offline replay from concurrent location edits. */
public final class RegionReplayJob {
    private final RegionRepository repository;
    private final Map<Long, Result> results;
    private final Clock clock;
    public RegionReplayJob(RegionRepository repository, Map<Long, Result> results, Clock clock) {
        this.repository = repository; this.results = Map.copyOf(results); this.clock = clock;
    }
    public Map<String, Object> run(boolean apply, int maxItems) throws Exception {
        if (maxItems < 1) throw new IllegalArgumentException("Positive maxItems required");
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, List<Long>> reviewIds = new LinkedHashMap<>();
        report.put("mode", apply ? "REPLAY_APPLY" : "REPLAY_DRY_RUN");
        report.put("inputResults", results.size()); report.put("apiCalls", 0);
        report.put("counts", counts); report.put("reviewIds", reviewIds);
        for (String key : List.of("scanned", "eligible", "applied", "alreadyStored", "missingCoordinates", "noResult", "staleOrChanged", "invalidResult", "concurrentChange", "inputToiletMissing")) counts.put(key, 0L);
        var unseen = new java.util.HashSet<>(results.keySet());
        long after = 0; int work = 0;
        boolean complete = true;
        try (AutoCloseable ignored = apply ? repository.lock() : () -> { }) {
            outer: while (true) {
                var page = repository.page(after, 100);
                if (page.isEmpty()) break;
                for (Source source : page) {
                    after = source.toiletId(); unseen.remove(after); increment(counts, "scanned");
                    Result result = results.get(after);
                    String skip = source.missing() ? "missingCoordinates" : result == null ? "noResult"
                            : !RegionJob.fresh(result, source, clock.millis()) ? "staleOrChanged"
                            : !valid(result) ? "invalidResult" : null;
                    if (skip != null) { increment(counts, skip); reviewIds.computeIfAbsent(skip, k -> new ArrayList<>()).add(after); continue; }
                    if (apply) {
                        Result stored = repository.stored(after);
                        if (stored != null && stored.checkedEpochMillis() >= result.checkedEpochMillis()) {
                            increment(counts, "alreadyStored"); continue;
                        }
                    }
                    increment(counts, "eligible"); increment(counts, result.status().name());
                    if (apply) {
                        var applied = repository.apply(result, false);
                        if (applied.conflict()) {
                            increment(counts, "concurrentChange");
                            reviewIds.computeIfAbsent("concurrentChange", k -> new ArrayList<>()).add(after);
                        } else increment(counts, "applied");
                    }
                    if (++work >= maxItems) { complete = false; break outer; }
                }
            }
        }
        if (complete) {
            counts.put("inputToiletMissing", (long) unseen.size());
            reviewIds.put("inputToiletMissing", unseen.stream().sorted().toList());
        }
        report.put("scanComplete", complete); report.put("lastScannedId", after);
        return report;
    }
    private static boolean valid(Result r) {
        if (!r.source().point().valid() || r.evaluated() == null || !r.evaluated().valid()
                || !r.source().point().key().equals(r.evaluated().key()) || !"NONE".equals(r.fallback())) return false;
        if (r.status() == Status.NO_COORDINATE || r.status() == Status.INVALID_COORDINATE) return false;
        if (r.status() != Status.VERIFIED) return true;
        Region region = r.region();
        return region != null && region.legalDongCode() != null && region.legalDongCode().matches("[1-9][0-9]{9}")
                && region.legalDongCode().substring(0, 2).equals(region.sidoCode())
                && region.legalDongCode().substring(0, 5).equals(region.sigunguCode());
    }
    private static void increment(Map<String, Long> counts, String key) { counts.merge(key, 1L, Long::sum); }
}
