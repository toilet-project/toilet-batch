package com.example.toiletbatch.account;

import com.geupddong.account.ErasureCompletion;
import com.geupddong.account.ErasureRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operator-supplied independent inventory. A checksum verifies a file, not its provenance. */
public record ErasureIntentInventory(int version, String realm, String databaseEpoch,
                                     Map<String, String> intentDigests) {
    public ErasureIntentInventory {
        if (version != 1 || realm == null || !realm.matches("[a-z0-9-]{3,40}")
                || !UUID.fromString(databaseEpoch).toString().equals(databaseEpoch)
                || intentDigests == null || intentDigests.size() > 100000)
            throw invalid();
        intentDigests = Map.copyOf(intentDigests);
        for (var entry : intentDigests.entrySet()) {
            String prefix = "v1/" + realm + "/";
            if (!entry.getKey().startsWith(prefix) || !entry.getKey().endsWith(".bin")) throw invalid();
            String id = entry.getKey().substring(prefix.length(), entry.getKey().length() - 4);
            if (!UUID.fromString(id).toString().equals(id) || !entry.getValue().matches("[a-f0-9]{64}")) throw invalid();
        }
    }

    public static ErasureIntentInventory read(Path file, String expectedSha256) {
        try {
            if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")
                    || Files.size(file) > 32 * 1024 * 1024) throw invalid();
            byte[] bytes;
            try (var in = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(32 * 1024 * 1024 + 1);
            }
            if (bytes.length > 32 * 1024 * 1024 || !expectedSha256.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)))) throw invalid();
            var factory = new com.fasterxml.jackson.core.JsonFactory();
            factory.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return new com.fasterxml.jackson.databind.ObjectMapper(factory).readValue(bytes, ErasureIntentInventory.class);
        } catch (Exception ignored) { throw invalid(); }
    }

    public void verify(List<ErasureRecord> records) {
        if (records.size() != intentDigests.size()) throw invalid();
        var seenKeys = new HashSet<String>();
        var seenUsers = new HashSet<Long>();
        for (var record : records) {
            if (!realm.equals(record.realm()) || !seenKeys.add(record.objectKey())
                    || !seenUsers.add(record.userId())
                    || !ErasureCompletion.digest(record).equals(intentDigests.get(record.objectKey()))) throw invalid();
        }
    }

    private static IllegalStateException invalid() { return new IllegalStateException("ERASURE_INVENTORY_INVALID"); }
}
