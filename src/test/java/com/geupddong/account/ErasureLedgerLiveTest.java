package com.geupddong.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

/** Opt-in, synthetic records only. No Spring context, SQL, Redis or production realm. */
@EnabledIfEnvironmentVariable(named = "ERASURE_LIVE_CHECK", matches = "true")
class ErasureLedgerLiveTest {
    // Shared non-secret run identity. Change both repositories together for a new paired check.
    private static final String REALM = "verification-20260906-c72e";
    private static final String BUCKET = "geupddong-account-erasure-ledger";
    private static final String ENDPOINT = "https://1611d88218de929b3ed6e2cd7c863be5.r2.cloudflarestorage.com";
    private static final ErasureRecord API = new ErasureRecord(1, REALM, Long.MAX_VALUE,
            "2000-01-01T00:00", "1a719e44-c7c5-4c72-84c0-b39d6acdd2e1", "2000-04-01T00:00");
    private static final ErasureRecord BATCH = new ErasureRecord(1, REALM, Long.MAX_VALUE - 1,
            "2000-01-01T00:00", "56f9be6f-10e0-4b6e-8c45-7b48cae4452e", "2000-04-01T00:00");

    @Test void githubSecretsCanExchangeSyntheticEncryptedRecords() {
        String stage = "configuration";
        try {
            String mode = required("ERASURE_LIVE_MODE");
            if (!List.of("write", "verify-cleanup").contains(mode)) throw new IllegalStateException();
            String keys = required("ERASURE_LEDGER_KEYS_JSON");
            Map<String,String> parsed = new ObjectMapper().readValue(keys, new TypeReference<>() {});
            if (parsed.size() != 1 || !parsed.containsKey("k1")) throw new IllegalStateException();
            // Never print key contents or fingerprints. Encryption compatibility proves equality.
            var env = new MockEnvironment().withProperty("erasure.ledger.realm", REALM)
                    .withProperty("erasure.ledger.bucket", BUCKET)
                    .withProperty("erasure.ledger.endpoint", ENDPOINT)
                    .withProperty("erasure.ledger.active-key-id", "k1")
                    .withProperty("erasure.ledger.keys-json", keys)
                    .withProperty("erasure.ledger.access-key-id", required("ERASURE_LEDGER_ACCESS_KEY_ID"))
                    .withProperty("erasure.ledger.secret-access-key", required("ERASURE_LEDGER_SECRET_ACCESS_KEY"));
            try (var ledger = ErasureLedgerFactory.configured(env)) {
                if ("write".equals(mode)) {
                    stage = "api-write-readback";
                    ledger.ensureRecorded(API);
                    if (!ledger.readAll(1).equals(List.of(API))) throw new IllegalStateException();
                    System.out.println("R2_LIVE_API_WRITE_READBACK_OK");
                } else {
                    stage = "cross-repository-decryption";
                    // Require API's object BEFORE any batch write, so a missing writer cannot pass.
                    if (!ledger.readAll(1).equals(List.of(API))) throw new IllegalStateException();
                    ledger.ensureRecorded(API);
                    stage = "batch-write-readback";
                    ledger.ensureRecorded(BATCH);
                    var records = ledger.readAll(2);
                    if (!records.containsAll(List.of(API, BATCH))) throw new IllegalStateException();
                    stage = "synthetic-cleanup";
                    try (var client = cleanupClient()) {
                        // Only these two freshly authenticated synthetic records can be deleted.
                        for (var record : List.of(API, BATCH))
                            client.deleteObject(r -> r.bucket(BUCKET).key(record.objectKey()));
                    }
                    if (!ledger.readAll(0).isEmpty()) throw new IllegalStateException();
                    System.out.println("R2_LIVE_CROSS_REPO_KEYS_BATCH_READ_WRITE_CLEANUP_OK");
                }
            }
        } catch (Exception ignored) {
            // Avoid AWS/Jackson error messages containing credentials or JSON input.
            throw new AssertionError("R2_LIVE_CHECK_FAILED stage=" + stage);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException();
        return value;
    }

    private static S3Client cleanupClient() {
        return S3Client.builder().endpointOverride(URI.create(ENDPOINT)).region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required("ERASURE_LEDGER_ACCESS_KEY_ID"), required("ERASURE_LEDGER_SECRET_ACCESS_KEY"))))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(4)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                .build();
    }
}

