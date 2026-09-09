package com.geupddong.account;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class FileErasureObjectStoreTest {
    private static final String ID = "01234567-1234-1234-1234-123456789012";
    private static final String REALM = "verification";
    @TempDir Path directory;
    private final ErasureRecord record = new ErasureRecord(1, REALM, 42, "2000-01-01T00:00", ID, "2000-04-01T00:00");
    private final ErasureCipher cipher = new ErasureCipher("test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
    private final TestSafety safety = new TestSafety();

    // Tests actual file bytes/locks/retries. DOES NOT claim to verify Linux permissions or directory fsync on Windows.
    static final class TestSafety implements FileErasureObjectStore.Safety {
        boolean failSync;
        boolean failCreate;
        int syncs;
        public void directory(Path p) throws IOException {
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        }
        public void file(Path root, Path p) throws IOException {
            var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) throw new IOException();
        }
        public void syncDirectory(Path p) throws IOException { syncs++; if (failSync) throw new IOException("private disk error"); }
        public void createFile(Path p) throws IOException {
            if (failCreate) throw new IOException("private disk error");
            Files.createFile(p);
        }
    }
    private FileErasureObjectStore store() throws IOException {
        Files.write(directory.resolve(".ledger-store"), FileErasureObjectStore.markerBytes(REALM, ID));
        if (!Files.exists(directory.resolve(".ledger.lock"))) Files.createFile(directory.resolve(".ledger.lock"));
        return reopen();
    }
    private FileErasureObjectStore reopen() {
        return new FileErasureObjectStore(directory, REALM, ID, safety);
    }
    private ObjectErasureLedger ledger(FileErasureObjectStore store) { return new ObjectErasureLedger(store, cipher, REALM, true); }
    private Path object(String key) { return directory.resolve(key.replace("/", "__")); }
    private void rejected(Runnable work) {
        var error = assertThrows(IllegalStateException.class, work::run);
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains(directory.toString()));
        assertFalse(error.getMessage().contains("private"));
    }

    @Test void encryptedRecordsSurviveReopenAndExactRetryDoesNotOverwrite() throws Exception {
        var store = store(); ledger(store).ensureRecorded(record);
        byte[] first = Files.readAllBytes(object(record.objectKey()));
        assertFalse(new String(first, java.nio.charset.StandardCharsets.UTF_8).contains("userCreatedAt"));
        int before = safety.syncs;
        ledger(reopen()).ensureRecorded(record);
        assertArrayEquals(first, Files.readAllBytes(object(record.objectKey())));
        assertTrue(safety.syncs >= before + 3);
        assertEquals(java.util.List.of(record), ledger(reopen()).readAll(1));
        assertEquals(java.util.List.of(record), ledger(reopen()).readCatalogue(1));
    }
    @Test void differentIdentityWithSameKeyNeverOverwritesOriginal() throws Exception {
        var ledger = ledger(store()); ledger.ensureRecorded(record);
        var different = new ErasureRecord(1, REALM, 43, record.userCreatedAt(), ID, record.eligibleAt());
        rejected(() -> ledger.ensureRecorded(different));
        assertEquals(java.util.List.of(record), ledger.readAll(1));
    }
    @Test void fsyncFailureBlocksAcknowledgementAndExactRetryReforcesExistingBytes() throws Exception {
        var store = store(); safety.failSync = true;
        byte[] original = cipher.encrypt(record);
        rejected(() -> store.putIfAbsent(record.objectKey(), original));
        assertArrayEquals(original, Files.readAllBytes(object(record.objectKey())));
        safety.failSync = false;
        store.putIfAbsent(record.objectKey(), cipher.encrypt(record));
        assertArrayEquals(original, store.read(record.objectKey()));
    }
    @Test void writeFailureDoesNotCreateAcknowledgedIntent() throws Exception {
        var ledger = ledger(store()); safety.failCreate = true;
        rejected(() -> ledger.ensureRecorded(record));
        assertFalse(Files.exists(object(record.objectKey())));
    }
    @Test void partialFileIsNotReplacedOrSilentlyRepaired() throws Exception {
        var ledger = ledger(store()); Files.write(object(record.objectKey()), new byte[]{1, 2, 3});
        rejected(() -> ledger.ensureRecorded(record));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(object(record.objectKey())));
    }
    @Test void corruptionAndOversizedFilesFailClosed() throws Exception {
        var store = store(); var ledger = ledger(store); ledger.ensureRecorded(record);
        byte[] bytes = Files.readAllBytes(object(record.objectKey())); bytes[35] ^= 1;
        Files.write(object(record.objectKey()), bytes); rejected(() -> ledger.readAll(1));
        Files.write(object(record.objectKey()), new byte[8193]); rejected(() -> store.read(record.objectKey()));
    }
    @Test void missingDirectoryOrMarkerNeverBootstrapsAnEmptyLedger() throws Exception {
        Path absent = directory.resolve("absent");
        rejected(() -> new FileErasureObjectStore(absent, REALM, ID, safety));
        assertFalse(Files.exists(absent));
        rejected(this::reopen);
        assertFalse(Files.exists(directory.resolve(".ledger-store")));
    }
    @Test void missingOrWrongMarkerAndMissingLockBlockAlreadyOpenedStore() throws Exception {
        var store = store();
        Files.write(directory.resolve(".ledger-store"), FileErasureObjectStore.markerBytes("other-realm", ID));
        rejected(() -> store.read(record.objectKey()));
        Files.write(directory.resolve(".ledger-store"), FileErasureObjectStore.markerBytes(REALM, ID));
        Files.delete(directory.resolve(".ledger.lock"));
        rejected(() -> store.read(record.objectKey()));
        assertFalse(Files.exists(directory.resolve(".ledger.lock")));
    }
    @Test void competingProcessLockBlocksAccessWithoutWriting() throws Exception {
        var store = store();
        try (var channel = FileChannel.open(directory.resolve(".ledger.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertTrue(lock.isValid());
            rejected(() -> store.putIfAbsent(record.objectKey(), cipher.encrypt(record)));
        }
        assertNull(store.read(record.objectKey()));
    }
    @Test void foreignRealmTraversalAndInvalidKeysAreRejected() throws Exception {
        var store = store();
        for (String key : new String[]{"../private", "/absolute", "v1/other/" + ID + ".bin", "v1/verification/not-uuid.bin", record.objectKey()+"/x"}) {
            rejected(() -> store.read(key)); rejected(() -> store.putIfAbsent(key, cipher.encrypt(record)));
        }
        rejected(() -> store.list("v1/other/", 3));
    }
    @Test void boundedInventoryRejectsExtraUnknownAndMissingObjects() throws Exception {
        var store = store(); var ledger = ledger(store); ledger.ensureRecorded(record);
        rejected(() -> ledger.readAll(0)); rejected(() -> ledger.readAll(2));
        rejected(() -> ledger.intentsAtMost(-1)); rejected(() -> ledger.intentsAtMost(1000001));
        Files.createFile(directory.resolve("unexpected")); rejected(() -> ledger.readAll(1));
    }
    @Test void completionRetainsFirstObservationAndRequiresMatchingIntent() throws Exception {
        var store = store(); var ledger = ledger(store);
        var now = Instant.parse("2026-09-08T00:00:00Z");
        var first = ErasureCompletion.observed(record, ID, now);
        rejected(() -> ledger.ensureCompletion(first));
        ledger.ensureRecorded(record);
        assertEquals(first, ledger.ensureCompletion(first));
        assertEquals(first, ledger.ensureCompletion(ErasureCompletion.observed(record, ID, now.plusSeconds(30))));
        rejected(() -> ledger.readCompletion(ErasureCompletion.observed(record, ID, now.minusSeconds(1))));
    }
    @Test void factoryRequiresExplicitAcceptanceAndNeverFallsBackToCloud() {
        var env = new MockEnvironment().withProperty("erasure.ledger.provider", "LOCAL")
                .withProperty("erasure.ledger.realm", REALM);
        rejected(() -> ErasureLedgerFactory.configured(env));
        env.withProperty("erasure.ledger.local-acceptance-verified", "true");
        rejected(() -> ErasureLedgerFactory.configured(env));
        env.withProperty("erasure.ledger.catalogue-enabled", "true")
                .withProperty("erasure.ledger.active-key-id", "test")
                .withProperty("erasure.ledger.keys-json", "{\"test\":\""+Base64.getEncoder().encodeToString(new byte[32])+"\"}")
                .withProperty("erasure.ledger.local-directory", directory.resolve("missing").toString())
                .withProperty("erasure.ledger.local-store-id", ID);
        rejected(() -> ErasureLedgerFactory.configured(env));
        assertFalse(Files.exists(directory.resolve("missing")));
    }
    @Test void disabledConfigurationNeverCreatesLocalFiles() {
        var env = new MockEnvironment().withProperty("erasure.ledger.provider", "LOCAL");
        rejected(() -> ErasureLedgerFactory.create(env).ensureRecorded(record));
        assertFalse(Files.exists(directory.resolve(".ledger-store")));
    }

    @Test void independentAcknowledgementFailureBlocksDeletionAndExactRetryResumes() throws Exception {
        var raw = ledger(store());
        var now = java.time.Instant.parse("2026-09-09T00:00:00Z");
        var empty = new ErasureCheckpoint(1,REALM,ID,1,0,"0".repeat(64),"",now.toString());
        var initial = new ErasureCheckpoint(1,REALM,ID,1,0,empty.inventoryDigest(Map.of()),"",now.toString());
        var head = new java.util.concurrent.atomic.AtomicReference<>(new CheckpointedErasureLedger.Head("one",initial));
        var failAck = new java.util.concurrent.atomic.AtomicBoolean(true);
        var checkpoint = new CheckpointedErasureLedger.Store() {
            public CheckpointedErasureLedger.Head read() { return head.get(); }
            public void append(CheckpointedErasureLedger.Head expected, ErasureCheckpoint next) {
                assertEquals(expected,head.get());
                if (failAck.getAndSet(false)) throw new IllegalStateException("private ack failure");
                head.set(new CheckpointedErasureLedger.Head("two",next));
            }
        };
        var protectedLedger = new CheckpointedErasureLedger(raw,checkpoint,Runnable::run,
                java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),REALM,ID);
        var deletionCalls = new java.util.concurrent.atomic.AtomicInteger();
        Runnable workflow = () -> { protectedLedger.ensureRecorded(record); deletionCalls.incrementAndGet(); };
        rejected(workflow); assertEquals(0,deletionCalls.get());
        assertEquals(0,head.get().checkpoint().count());
        workflow.run(); assertEquals(1,deletionCalls.get()); assertEquals(1,head.get().checkpoint().count());
        assertEquals(java.util.List.of(record),VerifiedErasureSnapshot.read(raw,checkpoint,REALM,ID,
                java.time.Clock.fixed(now,java.time.ZoneOffset.UTC)));
    }

    @Test void lostOtherMemberRecordPreventsFurtherErasure() throws Exception {
        var raw = ledger(store()); raw.ensureRecorded(record);
        var other = new ErasureRecord(1,REALM,43,record.userCreatedAt(),new java.util.UUID(0,43).toString(),record.eligibleAt());
        raw.ensureRecorded(other);
        var now = java.time.Instant.parse("2026-09-09T00:00:00Z");
        var seed = new ErasureCheckpoint(1,REALM,ID,1,0,"0".repeat(64),"",now.toString());
        var cp = new ErasureCheckpoint(1,REALM,ID,3,2,seed.inventoryDigest(Map.of(
                record.objectKey(),ErasureCompletion.digest(record),other.objectKey(),ErasureCompletion.digest(other))),"",now.toString());
        Files.delete(object(other.objectKey()));
        var checkpoint = new CheckpointedErasureLedger.Store() {
            public CheckpointedErasureLedger.Head read() { return new CheckpointedErasureLedger.Head("three",cp); }
            public void append(CheckpointedErasureLedger.Head h, ErasureCheckpoint next) { fail("must not advance"); }
        };
        var protectedLedger = new CheckpointedErasureLedger(raw,checkpoint,Runnable::run,
                java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),REALM,ID);
        rejected(() -> protectedLedger.ensureRecorded(record));
    }
}
