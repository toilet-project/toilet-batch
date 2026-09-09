package com.geupddong.account;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic local files only. Does not certify POSIX permissions/fsync on Windows. */
class FileErasureRetirementRemovalTest {
    private static final String ID = "01234567-1234-1234-1234-123456789012";
    private static final String REALM = "verification";
    @TempDir Path root;
    private final FileErasureObjectStoreTest.TestSafety safety = new FileErasureObjectStoreTest.TestSafety();
    private final ErasureRecord record = new ErasureRecord(1, REALM, 42, "2000-01-01T00:00", ID, "2000-04-01T00:00");
    private final ErasureCipher cipher = new ErasureCipher("test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));

    private FileErasureObjectStore create() throws Exception {
        Files.write(root.resolve(".ledger-store"), FileErasureObjectStore.markerBytes(REALM, ID));
        Files.createFile(root.resolve(".ledger.lock"));
        return reopen();
    }
    private FileErasureObjectStore reopen() { return new FileErasureObjectStore(root, REALM, ID, safety); }
    private Path path(String key) { return root.resolve(key.replace("/", "__")); }
    private String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private void rejected(Runnable action) {
        var ex = assertThrows(IllegalStateException.class, action::run);
        assertEquals("ERASURE_LOCAL_STORE_UNAVAILABLE", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test void exactCiphertextOnlyAndOtherObjectsRemainUnchanged() throws Exception {
        var store = create(); var ledger = new ObjectErasureLedger(store, cipher, REALM, true);
        ledger.ensureRecorded(record);
        var receipt = ErasureCompletion.observed(record, ID, Instant.parse("2026-09-01T00:00:00Z"));
        ledger.ensureCompletion(receipt);
        var original = new HashMap<String, byte[]>();
        for (String prefix : List.of("v1/", "catalogue-v1/", "completion-v1/"))
            for (String key : store.list(prefix + REALM + "/", 10)) original.put(key, store.read(key));
        assertEquals(3, original.size());
        assertEquals(FileErasureObjectStore.Removal.REMOVED,
                store.removeExact(record.objectKey(), digest(original.get(record.objectKey())), false));
        assertNull(store.read(record.objectKey()));
        for (var entry : original.entrySet())
            if (!entry.getKey().equals(record.objectKey())) assertArrayEquals(entry.getValue(), store.read(entry.getKey()));
        assertTrue(Files.exists(root.resolve(".ledger-store")));
        assertTrue(Files.exists(root.resolve(".ledger.lock")));
    }
    @Test void identityDigestIsNotCiphertextDigest() throws Exception {
        var store = create(); byte[] bytes = cipher.encrypt(record); store.putIfAbsent(record.objectKey(), bytes);
        rejected(() -> store.removeExact(record.objectKey(), ErasureCompletion.digest(record), false));
        assertArrayEquals(bytes, store.read(record.objectKey()));
    }
    @Test void reencryptedIdenticalIdentityIsNotTheApprovedFile() throws Exception {
        var store = create(); byte[] before = cipher.encrypt(record); byte[] replacement = cipher.encrypt(record);
        store.putIfAbsent(record.objectKey(), before);
        String expected = digest(before);
        Files.write(path(record.objectKey()), replacement);
        rejected(() -> store.removeExact(record.objectKey(), expected, true));
        assertArrayEquals(replacement, store.read(record.objectKey()));
    }
    @Test void missingFirstAttemptFailsButExplicitPreparedRetryReforcesDirectory() throws Exception {
        var store = create(); String expected = digest(cipher.encrypt(record));
        rejected(() -> store.removeExact(record.objectKey(), expected, false));
        int before = safety.syncs;
        assertEquals(FileErasureObjectStore.Removal.ALREADY_ABSENT, store.removeExact(record.objectKey(), expected, true));
        assertEquals(before + 1, safety.syncs);
    }
    @Test void lostFsyncAcknowledgementResumesAfterReopenWithoutRecreatingRecord() throws Exception {
        var store = create(); byte[] bytes = cipher.encrypt(record); store.putIfAbsent(record.objectKey(), bytes);
        String expected = digest(bytes); safety.failSync = true;
        rejected(() -> store.removeExact(record.objectKey(), expected, false));
        assertFalse(Files.exists(path(record.objectKey())));
        rejected(() -> store.removeExact(record.objectKey(), expected, true));
        safety.failSync = false;
        var resumed = reopen(); int before = safety.syncs;
        assertEquals(FileErasureObjectStore.Removal.ALREADY_ABSENT, resumed.removeExact(record.objectKey(), expected, true));
        assertEquals(before + 1, safety.syncs);
        assertNull(resumed.read(record.objectKey()));
    }
    @Test void retryStillRejectsDifferentContentThatReappears() throws Exception {
        var store = create(); byte[] bytes = cipher.encrypt(record); store.putIfAbsent(record.objectKey(), bytes);
        String expected = digest(bytes); store.removeExact(record.objectKey(), expected, false);
        byte[] replacement = cipher.encrypt(record); store.putIfAbsent(record.objectKey(), replacement);
        rejected(() -> store.removeExact(record.objectKey(), expected, true));
        assertArrayEquals(replacement, store.read(record.objectKey()));
    }
    @Test void competingStoreLockPreventsDeletion() throws Exception {
        var store = create(); byte[] bytes = cipher.encrypt(record); store.putIfAbsent(record.objectKey(), bytes);
        String expected = digest(bytes);
        try (var channel = FileChannel.open(root.resolve(".ledger.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertTrue(lock.isValid());
            rejected(() -> store.removeExact(record.objectKey(), expected, false));
            assertArrayEquals(bytes, Files.readAllBytes(path(record.objectKey())));
        }
    }
    @Test void metadataTraversalAndForeignRealmCannotBeRemovalTargets() throws Exception {
        var store = create(); String expected = digest(cipher.encrypt(record));
        for (String key : List.of(".ledger-store", ".ledger.lock", "../anything", "/absolute", "v1/other/" + ID + ".bin"))
            rejected(() -> store.removeExact(key, expected, true));
        assertTrue(Files.exists(root.resolve(".ledger.lock")));
    }
    @Test void invalidHashDoesNotTouchExistingFile() throws Exception {
        var store = create(); byte[] bytes = cipher.encrypt(record); store.putIfAbsent(record.objectKey(), bytes);
        for (String hash : Arrays.asList(null, "", "A".repeat(64), "0".repeat(63), "g".repeat(64)))
            rejected(() -> store.removeExact(record.objectKey(), hash, true));
        assertArrayEquals(bytes, store.read(record.objectKey()));
    }
    @Test void markerLossBlocksDeletionEvenForAnAlreadyAbsentRetry() throws Exception {
        var store = create(); String expected = digest(cipher.encrypt(record));
        Files.delete(root.resolve(".ledger-store"));
        rejected(() -> store.removeExact(record.objectKey(), expected, true));
        assertFalse(Files.exists(root.resolve(".ledger-store")));
    }
    @Test void directoryAndPartialFileAreNotSilentlyRemoved() throws Exception {
        var store = create(); String key = record.objectKey();
        Files.createDirectory(path(key));
        rejected(() -> store.removeExact(key, "0".repeat(64), true));
        assertTrue(Files.isDirectory(path(key)));
        Files.delete(path(key)); byte[] partial = {1, 2, 3}; Files.write(path(key), partial);
        String hash = digest(partial);
        rejected(() -> store.removeExact(key, hash, false));
        assertArrayEquals(partial, Files.readAllBytes(path(key)));
    }
}
