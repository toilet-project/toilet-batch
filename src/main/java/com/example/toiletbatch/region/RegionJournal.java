package com.example.toiletbatch.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import static com.example.toiletbatch.region.RegionModel.*;

/** Durable per-item checkpoint + coordinate cache + daily call budget, all under one process lock. */
public final class RegionJournal implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<Long, Result> results = new HashMap<>();
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    public record Cached(Region region, long at) { }

    public RegionJournal(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Region journal is already locked");
            lock = acquired;
            // A killed process can leave a partial final line. Only that uncommitted tail may be discarded.
            long end = channel.size();
            ByteBuffer last = ByteBuffer.allocate(1);
            while (end > 0) {
                last.clear(); channel.read(last, end - 1);
                if (last.array()[0] == '\n') break;
                end--;
            }
            channel.truncate(end);
            channel.position(0);
            // Read via the locked handle: Windows rejects a second handle reading the locked range.
            var reader = new java.io.BufferedReader(java.nio.channels.Channels.newReader(channel, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) replay(json.readTree(line));
            channel.position(channel.size());
        } catch (IOException | RuntimeException e) {
            if (acquired != null) acquired.close();
            channel.close(); throw e;
        }
    }
    private void replay(JsonNode node) throws IOException {
        switch (node.path("kind").asText()) {
            case "result" -> { Result r = json.treeToValue(node.path("value"), Result.class); results.put(r.source().toiletId(), r); }
            case "reverse" -> cache.put(node.path("key").asText(), json.treeToValue(node.path("value"), Cached.class));
            case "call" -> calls.merge(node.path("day").asText(), 1, Integer::sum);
            default -> throw new IOException("Unknown journal entry; use matching normalizer version");
        }
    }
    private void append(Map<String, ?> entry) {
        try {
            ByteBuffer bytes = ByteBuffer.wrap((json.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException e) { throw new RegionProvider.Stop("JOURNAL_WRITE_FAILED"); }
    }
    public Result get(long id) { return results.get(id); }
    public void record(Result result) { append(Map.of("kind", "result", "value", result)); results.put(result.source().toiletId(), result); }
    public Region cached(Point point, long now) {
        Cached value = cache.get(VERSION + ":" + point.key());
        return value != null && now - value.at() < 30L * 86400000 ? value.region() : null;
    }
    public void cache(Point point, Region region, long now) {
        String key = VERSION + ":" + point.key(); Cached value = new Cached(region, now);
        append(Map.of("kind", "reverse", "key", key, "value", value)); cache.put(key, value);
    }
    public void reserveCall(Clock clock, int dailyBudget) {
        String day = LocalDate.now(clock).toString();
        if (calls.getOrDefault(day, 0) >= dailyBudget) throw new RegionProvider.Stop("DAILY_CALL_BUDGET_EXHAUSTED");
        // Reserve before sending; a crash may overcount, but must never undercount traffic.
        append(Map.of("kind", "call", "day", day)); calls.merge(day, 1, Integer::sum);
    }
    public int callsToday(Clock clock) { return calls.getOrDefault(LocalDate.now(clock).toString(), 0); }
    @Override public void close() throws IOException { lock.close(); channel.close(); }
}
