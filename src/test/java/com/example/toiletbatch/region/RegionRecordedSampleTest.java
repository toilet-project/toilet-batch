package com.example.toiletbatch.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional operator-owned captured API responses; no credentials, network, or production DB access. */
class RegionRecordedSampleTest {
    @TempDir Path temp;
    @Test void replayAllThousandIncludingThirtySevenExceptions() throws Exception {
        String root = System.getenv("REGION_REPLAY_DIR");
        assumeTrue(root != null, "Set REGION_REPLAY_DIR to the approved recorded analysis directory");
        var json = new ObjectMapper();
        Map<Long, JsonNode> followups = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(root, "region-random1000-followup.jsonl"))) {
            JsonNode row = json.readTree(line);
            if ("result".equals(row.path("kind").asText())) followups.put(row.path("value").path("id").asLong(), row.path("value"));
        }
        int total = 0, verified = 0, reviewed = 0;
        var reviewIds = new java.util.HashSet<Long>();
        try (var journal = new RegionJournal(temp.resolve("parse-only.jsonl"))) {
            var parser = new KakaoRegionProvider("fixture-only", journal, Clock.systemUTC(), 1, 200);
            for (String line : Files.readAllLines(Path.of(root, "region-random1000.region.jsonl"))) {
                JsonNode row = json.readTree(line);
                if (!"result".equals(row.path("kind").asText())) continue;
                Result old = json.treeToValue(row.path("value"), Result.class);
                JsonNode captured = followups.get(old.source().toiletId());
                RegionProvider provider = new RegionProvider() {
                    public Optional<Point> geocodeUnique(String address) { fail("Existing coordinates must not be regenerated"); return Optional.empty(); }
                    public Region reverse(Point point) { assertEquals(old.evaluated(), point); return old.region(); }
                    public ReverseAddress reverseAddress(Point point) {
                        assertNotNull(captured, "Unexpected supplementary request for " + old.source().toiletId());
                        return parser.parseReverseAddress(payload(json, captured.path("reverseAddress")));
                    }
                    public AddressLookup searchAddress(String address) {
                        String field = address.equals(old.source().roadAddress()) ? "roadAddress" : "jibunAddress";
                        for (JsonNode forward : captured.path("forward"))
                            if (field.equals(forward.path("field").asText())) return parser.parseAddressLookup(payload(json, forward.path("response")));
                        throw new AssertionError("Missing recorded address");
                    }
                };
                Result result = new RegionNormalizer(provider, Clock.systemUTC()).normalize(old.source());
                assertEquals(old.source(), result.source()); assertEquals(old.evaluated(), result.evaluated());
                assertNotEquals(Status.REVERSE_FAILED, result.status(), "id=" + old.source().toiletId() + " " + result.evidence());
                total++;
                if (result.status() == Status.VERIFIED) verified++; else { reviewed++; reviewIds.add(old.source().toiletId()); }
            }
            assertEquals(1000, total); assertEquals(37, followups.size());
            assertEquals(991, verified); assertEquals(9, reviewed);
            assertEquals(java.util.Set.of(16501L, 45043L, 45217L, 45564L, 49027L, 52159L, 52160L, 52205L, 52638L), reviewIds);
            assertEquals(0, journal.callsToday(Clock.systemUTC()));
        }
    }
    static String payload(ObjectMapper json, JsonNode recorded) {
        ObjectNode root = json.createObjectNode();
        root.putObject("meta").set("total_count", recorded.path("totalCount"));
        root.set("documents", recorded.path("documents")); return root.toString();
    }
}
