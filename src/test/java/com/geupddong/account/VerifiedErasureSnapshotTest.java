package com.geupddong.account;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedErasureSnapshotTest {
    private static final String REALM = "verification", EPOCH = "01234567-1234-1234-1234-123456789012";
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Map<String,byte[]> files = new TreeMap<>();
    private final ErasureCipher cipher = new ErasureCipher("test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
    private final ErasureObjectStore objects = new ErasureObjectStore() {
        public byte[] read(String key) { return files.get(key); }
        public void putIfAbsent(String key, byte[] value) { files.putIfAbsent(key, value); }
        public List<String> list(String prefix, int max) { return files.keySet().stream().filter(k -> k.startsWith(prefix)).toList(); }
    };
    private final ObjectErasureLedger ledger = new ObjectErasureLedger(objects, cipher, REALM, true);
    private ErasureRecord record(long user) {
        return new ErasureRecord(1, REALM, user, "2000-01-01T00:00", new UUID(0,user).toString(), "2000-04-01T00:00");
    }
    private ErasureCheckpoint checkpoint(List<ErasureRecord> records) {
        var empty = new ErasureCheckpoint(1, REALM, EPOCH, 1, 0, "0".repeat(64), "", NOW.toString());
        var map = new TreeMap<String,String>();
        records.forEach(r -> map.put(r.objectKey(), ErasureCompletion.digest(r)));
        return new ErasureCheckpoint(1, REALM, EPOCH, 1, map.size(), empty.inventoryDigest(map), "", NOW.toString());
    }
    private CheckpointedErasureLedger.Store store(ErasureCheckpoint cp) {
        return new CheckpointedErasureLedger.Store() {
            public CheckpointedErasureLedger.Head read() { return new CheckpointedErasureLedger.Head("revision", cp); }
            public void append(CheckpointedErasureLedger.Head expected, ErasureCheckpoint next) { fail("read-only gate"); }
        };
    }
    private void denied(CheckpointedErasureLedger.Store store) {
        var error = assertThrows(IllegalStateException.class, () -> VerifiedErasureSnapshot.read(ledger, store, REALM, EPOCH, clock));
        assertEquals("ERASURE_RESTORE_INVENTORY_UNVERIFIED", error.getMessage()); assertNull(error.getCause());
    }
    @Test void exactIndependentSnapshotIsAcceptedWithoutWrites() {
        var record = record(42); ledger.ensureRecorded(record); var before = new TreeMap<>(files);
        assertEquals(List.of(record), VerifiedErasureSnapshot.read(ledger, store(checkpoint(List.of(record))), REALM, EPOCH, clock));
        assertEquals(before, files);
    }
    @Test void emptyIsOnlyAcceptedWithIndependentZeroBaseline() {
        assertTrue(VerifiedErasureSnapshot.read(ledger, store(checkpoint(List.of())), REALM, EPOCH, clock).isEmpty());
        denied(store(checkpoint(List.of(record(42)))));
    }
    @Test void coherentLocalRollbackWithSameCountIsRejected() {
        ledger.ensureRecorded(record(41)); denied(store(checkpoint(List.of(record(42)))));
    }
    @Test void missingIntentOrCatalogueIsRejected() {
        var r = record(42); ledger.ensureRecorded(r); var cp = checkpoint(List.of(r));
        files.remove(r.objectKey()); denied(store(cp));
        ledger.ensureRecorded(r); files.remove("catalogue-v1/"+REALM+"/"+r.withdrawalKey()+".bin"); denied(store(cp));
    }
    @Test void catalogueAndIntentMustAgreeNotJustCounts() {
        ledger.ensureRecorded(record(42)); var cp = checkpoint(List.of(record(42)));
        files.keySet().removeIf(k -> k.startsWith("catalogue-v1/"));
        var other = record(43); String key = "catalogue-v1/"+REALM+"/"+other.withdrawalKey()+".bin";
        try { files.put(key, cipher.encryptDocument(REALM,key,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(other))); }
        catch (Exception e) { throw new AssertionError(e); }
        denied(store(cp));
    }
    @Test void checkpointFailureAndChangingRevisionAreRejected() {
        var cp = checkpoint(List.of()); var reads = new AtomicInteger();
        denied(new CheckpointedErasureLedger.Store() {
            public CheckpointedErasureLedger.Head read() { return new CheckpointedErasureLedger.Head("revision"+reads.incrementAndGet(),cp); }
            public void append(CheckpointedErasureLedger.Head h, ErasureCheckpoint n) { fail(); }
        });
        denied(new CheckpointedErasureLedger.Store() {
            public CheckpointedErasureLedger.Head read() { throw new IllegalStateException("private token"); }
            public void append(CheckpointedErasureLedger.Head h, ErasureCheckpoint n) { fail(); }
        });
    }
    @Test void wrongEpochAndFutureCheckpointAreRejected() {
        var cp = checkpoint(List.of());
        denied(store(new ErasureCheckpoint(1, REALM, new UUID(0,5).toString(), 1, 0, cp.inventorySha256(), "", NOW.toString())));
        denied(store(new ErasureCheckpoint(1, REALM, EPOCH, 1, 0, cp.inventorySha256(), "", NOW.plusSeconds(1).toString())));
    }
    @Test void duplicateMemberWithDistinctKeysIsRejected() {
        var a = record(42); var b = new ErasureRecord(1, REALM, 42, a.userCreatedAt(), new UUID(0,43).toString(),a.eligibleAt());
        ledger.ensureRecorded(a); ledger.ensureRecorded(b); denied(store(checkpoint(List.of(a,b))));
    }
    @Test void unacknowledgedExtraLocalRecordCannotBeUsedForRestore() {
        ledger.ensureRecorded(record(42)); ledger.ensureRecorded(record(43)); denied(store(checkpoint(List.of(record(42)))));
    }
}
