package com.geupddong.account;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.mock.env.MockEnvironment;

/** Standalone synthetic-only Linux probe. No DB, Redis, cloud credentials, Spring context or deployment. */
public final class LocalLedgerLinuxVerification {
    private static final byte[] SENTINEL = "LOCAL_LEDGER_SYNTHETIC_ONLY\n".getBytes(StandardCharsets.US_ASCII);
    private static void require(boolean condition) { if (!condition) throw new IllegalStateException("LOCAL_VERIFICATION_FAILED"); }
    private static void rejected(Runnable action) {
        boolean failed = false;
        try { action.run(); } catch (IllegalStateException expected) { failed = true; }
        require(failed);
    }
    static void validateParent(Path parent) throws Exception {
        require(parent.isAbsolute() && parent.equals(parent.normalize()) && parent.equals(parent.toRealPath()));
        require(parent.getFileName().toString().matches("erasure-local-verification-[a-f0-9]{16}"));
        require(!Files.isSymbolicLink(parent));
        require(Arrays.equals(SENTINEL, Files.readAllBytes(parent.resolve("SYNTHETIC_ONLY"))));
        new FileErasureObjectStore.LinuxSafety().directory(parent);
    }
    private static void newPrivateFile(Path file, byte[] bytes) throws Exception {
        try (var channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static CheckpointedErasureLedger.Store checkpoint(ErasureRecord record, String epoch, Instant now) {
        var map = Map.of(record.objectKey(), ErasureCompletion.digest(record));
        var empty = new ErasureCheckpoint(1, record.realm(), epoch, 1, 0, "0".repeat(64), "", now.toString());
        var cp = new ErasureCheckpoint(1, record.realm(), epoch, 2, 1, empty.inventoryDigest(map), "", now.toString());
        return new CheckpointedErasureLedger.Store() {
            public CheckpointedErasureLedger.Head read() { return new CheckpointedErasureLedger.Head("synthetic", cp); }
            public void append(CheckpointedErasureLedger.Head before, ErasureCheckpoint next) { throw new AssertionError("read-only"); }
        };
    }
    public static void main(String[] args) {
        String stage = "guard";
        int passed = 0;
        try {
            require("approved".equals(System.getenv("ERASURE_LOCAL_SYNTHETIC_CHECK")));
            if (args.length == 2 && args[0].equals("--hold-lock")) {
                Path dir = Path.of(args[1]); validateParent(dir.getParent());
                require(dir.getFileName().toString().startsWith("ledger-"));
                new FileErasureObjectStore.LinuxSafety().file(dir, dir.resolve(".ledger.lock"));
                try (var channel = FileChannel.open(dir.resolve(".ledger.lock"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     var lock = channel.lock()) {
                    require(lock.isValid()); System.out.println("LOCKED"); System.out.flush(); System.in.read();
                }
                return;
            }
            require(args.length == 1);
            Path parent = Path.of(args[0]); validateParent(parent);
            String realm = "verification", id = UUID.randomUUID().toString();
            Path root = Files.createTempDirectory(parent, "ledger-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            stage = "provision-synthetic";
            newPrivateFile(root.resolve(".ledger-store"), FileErasureObjectStore.markerBytes(realm, id));
            newPrivateFile(root.resolve(".ledger.lock"), new byte[]{0});
            var safety = new FileErasureObjectStore.LinuxSafety(); safety.syncDirectory(root); safety.syncDirectory(parent);
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            var env = new MockEnvironment().withProperty("erasure.ledger.provider", "LOCAL")
                    .withProperty("erasure.ledger.local-acceptance-verified", "true")
                    .withProperty("erasure.ledger.catalogue-enabled", "true")
                    .withProperty("erasure.ledger.local-directory", root.toString())
                    .withProperty("erasure.ledger.local-store-id", id).withProperty("erasure.ledger.realm", realm)
                    .withProperty("erasure.ledger.active-key-id", "synthetic")
                    .withProperty("erasure.ledger.keys-json", "{\"synthetic\":\""+Base64.getEncoder().encodeToString(key)+"\"}");
            Arrays.fill(key, (byte)0);
            var record = new ErasureRecord(1, realm, 42, "2000-01-01T00:00", UUID.randomUUID().toString(), "2000-04-01T00:00");
            Instant now = Instant.now(); var clock = Clock.fixed(now, ZoneOffset.UTC);
            var store = checkpoint(record, id, now);
            Path intent = root.resolve(record.objectKey().replace("/", "__"));
            stage = "durable-write-and-reopen";
            try (var ledger = ErasureLedgerFactory.configured(env)) { ledger.ensureRecorded(record); }
            byte[] original = Files.readAllBytes(intent);
            try (var ledger = ErasureLedgerFactory.configured(env)) {
                require(VerifiedErasureSnapshot.read(ledger, store, realm, id, clock).equals(List.of(record))); passed++;
                ledger.ensureRecorded(record); require(Arrays.equals(original, Files.readAllBytes(intent))); passed++;
                stage = "completion";
                var completion = ErasureCompletion.observed(record, id, now);
                require(completion.equals(ledger.ensureCompletion(completion)));
                require(completion.equals(ledger.ensureCompletion(ErasureCompletion.observed(record,id,now.plusSeconds(30))))); passed++;
                stage = "permissions";
                Files.setPosixFilePermissions(intent, PosixFilePermissions.fromString("rw-r-----"));
                rejected(() -> ledger.readAll(1)); passed++;
                Files.setPosixFilePermissions(intent, PosixFilePermissions.fromString("rw-------"));
                stage = "links";
                Path link = root.resolve("v1__verification__"+UUID.randomUUID()+".bin");
                Files.createSymbolicLink(link, intent); rejected(() -> ledger.readAll(1)); passed++; Files.delete(link);
                Files.createLink(link, intent); rejected(() -> ledger.readAll(1)); passed++; Files.delete(link);
                stage = "cross-process-lock";
                var child = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                        "-cp",System.getProperty("java.class.path"),LocalLedgerLinuxVerification.class.getName(),"--hold-lock",root.toString()).start();
                try {
                    var reader = child.inputReader();
                    var greeting = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try { return reader.readLine(); } catch (Exception e) { return null; }
                    }).get(10,TimeUnit.SECONDS);
                    require("LOCKED".equals(greeting)); rejected(() -> ledger.ensureRecorded(record)); passed++;
                    child.getOutputStream().write(1); child.getOutputStream().flush();
                    require(child.waitFor(10,TimeUnit.SECONDS) && child.exitValue()==0);
                } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10,TimeUnit.SECONDS); } }
                stage = "rollback-and-corruption";
                var other = new ErasureRecord(1,realm,43,record.userCreatedAt(),record.withdrawalKey(),record.eligibleAt());
                rejected(() -> VerifiedErasureSnapshot.read(ledger,checkpoint(other,id,now),realm,id,clock)); passed++;
                byte[] corrupt = original.clone(); corrupt[35] ^= 1; Files.write(intent,corrupt);
                rejected(() -> VerifiedErasureSnapshot.read(ledger,store,realm,id,clock)); passed++;
                Files.write(intent,original);
                stage = "missing-catalogue";
                Path catalogue = root.resolve("catalogue-v1__"+realm+"__"+record.withdrawalKey()+".bin");
                byte[] saved = Files.readAllBytes(catalogue); Files.delete(catalogue);
                rejected(() -> VerifiedErasureSnapshot.read(ledger,store,realm,id,clock)); passed++;
                newPrivateFile(catalogue,saved); safety.syncDirectory(root);
                require(VerifiedErasureSnapshot.read(ledger,store,realm,id,clock).equals(List.of(record))); passed++;
            }
            stage = "missing-marker";
            Files.delete(root.resolve(".ledger-store"));
            rejected(() -> ErasureLedgerFactory.configured(env)); passed++;
            // Synthetic files remain in the newly-created directory for inspection. Nothing outside it is removed.
            System.out.printf("LOCAL_LEDGER_SYNTHETIC_VERIFIED checks=%d productionReads=0 productionWrites=0 cloudCalls=0 retainedSyntheticDirectory=true%n",passed);
        } catch (Exception ignored) {
            System.err.printf("LOCAL_LEDGER_SYNTHETIC_FAILED stage=%s checks=%d%n",stage,passed); System.exit(1);
        }
    }
}
