package com.geupddong.account;

import java.nio.file.*;
import java.net.URI;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Explicit CI-only rehearsal with synthetic encrypted SQL and a random non-production R2 prefix. */
@EnabledIfEnvironmentVariable(named="ERASURE_RESTORE_LIVE_CHECK", matches="true")
class AccountRestoreRehearsalLiveTest {
    @TempDir Path temporary;
    private static final String BUCKET = "geupddong-account-erasure-ledger";
    private static final String ENDPOINT = "https://1611d88218de929b3ed6e2cd7c863be5.r2.cloudflarestorage.com";

    @Test void encryptedBackupIsRestoredAndReErasedWithoutTouchingLiveSystems() {
        String stage = "fixture";
        String realm = "verify-restore-" + UUID.randomUUID().toString().replace("-", "").substring(0,16);
        var record = new ErasureRecord(1, realm, 1, "2000-01-01T00:00", UUID.randomUUID().toString(), "2000-04-01T00:00");
        boolean recorded = false;
        boolean cleanupOk = true;
        try {
            Path key = temporary.resolve("backup.key"), gzip = temporary.resolve("fixture.sql.gz"), backup = temporary.resolve("fixture.sql.gz.enc");
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            Files.writeString(key, Base64.getEncoder().encodeToString(random));
            try (var input = getClass().getResourceAsStream("/account-erasure-restore-fixture.sql");
                 var output = new GZIPOutputStream(Files.newOutputStream(gzip))) {
                Objects.requireNonNull(input).transferTo(output);
            }
            if (process(List.of("openssl","enc","-aes-256-cbc","-salt","-pbkdf2","-iter","200000","-pass","file:"+key,"-in",gzip.toString(),"-out",backup.toString()), Map.of()).exit() != 0)
                throw new IllegalStateException();
            Files.delete(gzip);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup)));
            Files.writeString(Path.of(backup+".sha256"), hash+"  fixture.sql.gz.enc\n");
            var env = new HashMap<String,String>();
            env.put("GEUPDDONG_BACKUP_KEY_FILE", key.toString());
            env.put("GEUPDDONG_ERASURE_TOOL_DIR", Path.of("build/erasure-tools").toAbsolutePath().toString());
            env.put("ERASURE_LEDGER_REALM",realm); env.put("ERASURE_LEDGER_BUCKET",BUCKET);
            env.put("ERASURE_LEDGER_ENDPOINT",ENDPOINT); env.put("ERASURE_LEDGER_ACTIVE_KEY_ID","k1");
            env.put("ERASURE_RESTORE_WRITERS_STOPPED","true"); env.put("ERASURE_RESTORE_INVENTORY_CONFIRMED","true");
            env.put("ERASURE_RESTORE_EXPECTED_OBJECTS","1");
            stage = "write-synthetic-intent";
            try (var ledger = ErasureLedgerFactory.configured(environment(realm))) {
                // Mark before call so a lost acknowledgement is cleaned up too.
                recorded = true;
                ledger.ensureRecorded(record);
                stage = "encrypted-backup-import-and-replay";
                var command = List.of("bash","scripts/mysql-restore-erasure-verify.sh",backup.toString());
                var result = process(command, env);
                if (result.exit() != 0) {
                    // Only fixed script/CLI codes; never dump process output, SQL, or environment.
                    String code = result.output().lines().filter(line -> line.matches("ERASURE_(REHEARSAL|RESTORE)_FAILED: [a-zA-Z0-9 ;,.-]+"))
                            .reduce((a,b) -> a+" | "+b).orElse("subprocess-exit-"+result.exit());
                    throw new AssertionError("RESTORE_REHEARSAL_FAILED " + code);
                }
                if (!result.output().contains("ERASURE_REHEARSAL_OK before_users=2 after_users=1 reports=1 toilets=1")
                        || !result.output().contains("dryRun=false records=1 matched=1 absent=0 erased=1")
                        || !result.output().contains("dryRun=true records=1 matched=0 absent=1 erased=0")) throw new IllegalStateException();
                stage = "inventory-mismatch-must-fail";
                env.put("ERASURE_RESTORE_EXPECTED_OBJECTS","0");
                var mismatch = process(command, env);
                if (mismatch.exit() == 0 || !mismatch.output().contains("ERASURE_REHEARSAL_FAILED: dry-run")) throw new IllegalStateException();
                if (!ledger.readAll(1).equals(List.of(record))) throw new IllegalStateException();
            }
        } catch (Exception ignored) {
            throw new AssertionError("RESTORE_REHEARSAL_FAILED stage="+stage);
        } finally {
            if (recorded) {
                try (var client = S3Client.builder().endpointOverride(URI.create(ENDPOINT)).region(Region.of("auto"))
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                                System.getenv("ERASURE_LEDGER_ACCESS_KEY_ID"),System.getenv("ERASURE_LEDGER_SECRET_ACCESS_KEY"))))
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))) .build()) {
                    client.deleteObject(r -> r.bucket(BUCKET).key(record.objectKey()));
                    try (var ledger = ErasureLedgerFactory.configured(environment(realm))) { ledger.readAll(0); }
                } catch (Exception ignored) { cleanupOk = false; }
                if (!cleanupOk) throw new AssertionError("RESTORE_REHEARSAL_SYNTHETIC_CLEANUP_FAILED");
            }
        }
    }

    private MockEnvironment environment(String realm) {
        return new MockEnvironment().withProperty("erasure.ledger.realm",realm)
                .withProperty("erasure.ledger.bucket",BUCKET).withProperty("erasure.ledger.endpoint",ENDPOINT)
                .withProperty("erasure.ledger.active-key-id","k1")
                .withProperty("erasure.ledger.keys-json",System.getenv("ERASURE_LEDGER_KEYS_JSON"))
                .withProperty("erasure.ledger.access-key-id",System.getenv("ERASURE_LEDGER_ACCESS_KEY_ID"))
                .withProperty("erasure.ledger.secret-access-key",System.getenv("ERASURE_LEDGER_SECRET_ACCESS_KEY"));
    }
    private Result process(List<String> command, Map<String,String> env) throws Exception {
        Path output = temporary.resolve(UUID.randomUUID()+".log");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(env);
        var process = builder.start();
        if (!process.waitFor(5,TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IllegalStateException(); }
        String text = Files.readString(output); Files.delete(output);
        return new Result(process.exitValue(),text);
    }
    private record Result(int exit,String output) { }
}
