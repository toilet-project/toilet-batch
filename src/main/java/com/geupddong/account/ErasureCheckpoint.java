package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Aggregate-only JSON, byte-compatible with the independently established checkpoint format. */
public record ErasureCheckpoint(int version, String realm, String databaseEpoch, long sequence,
        int count, String inventorySha256, String previousCheckpointSha256, String recordedAt) {
    public ErasureCheckpoint {
        if (version != 1 || realm == null || !realm.matches("[a-z0-9-]{3,40}")
                || !UUID.fromString(databaseEpoch).toString().equals(databaseEpoch)
                || sequence < 1 || count < 0 || count > 100000 || !inventorySha256.matches("[a-f0-9]{64}")
                || !(previousCheckpointSha256.isEmpty() || previousCheckpointSha256.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("ERASURE_CHECKPOINT_INVALID");
        Instant.parse(recordedAt);
    }
    public byte[] bytes() {
        try { return new ObjectMapper().writeValueAsBytes(this); }
        catch (Exception e) { throw new IllegalStateException("ERASURE_CHECKPOINT_INVALID"); }
    }
    public String digest() { return hash(bytes()); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("ERASURE_CHECKPOINT_INVALID"); }
    }
    public String inventoryDigest(Map<String,String> records) {
        try {
            var value = new LinkedHashMap<String,Object>();
            value.put("version", 1); value.put("realm", realm); value.put("databaseEpoch", databaseEpoch);
            value.put("intentDigests", new TreeMap<>(records));
            return hash(new ObjectMapper().writeValueAsBytes(value));
        } catch (Exception e) { throw new IllegalStateException("ERASURE_CHECKPOINT_INVALID"); }
    }
}
