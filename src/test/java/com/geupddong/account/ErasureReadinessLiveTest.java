package com.geupddong.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Read-only observation. No Spring context, object contents, writes, DB or Redis. */
@EnabledIfEnvironmentVariable(named = "ERASURE_READINESS_READ_ONLY", matches = "true")
class ErasureReadinessLiveTest {
    @Test void observeBucketWithoutEstablishingBaseline() {
        String stage = "configuration";
        try {
            String endpoint = required("ERASURE_LEDGER_ENDPOINT");
            // Restrict this operational check to the previously verified account/bucket.
            if (!endpoint.equals("https://1611d88218de929b3ed6e2cd7c863be5.r2.cloudflarestorage.com")
                    || !required("ERASURE_LEDGER_BUCKET").equals("geupddong-account-erasure-ledger"))
                throw new IllegalStateException();
            Map<String, String> keys = new ObjectMapper().readValue(
                    required("ERASURE_LEDGER_KEYS_JSON"), new TypeReference<>() { });
            new ErasureCipher("k1", keys); // Format only; does not prove backup recoverability.
            try (var client = S3Client.builder().endpointOverride(URI.create(endpoint))
                    .region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            required("ERASURE_LEDGER_ACCESS_KEY_ID"),
                            required("ERASURE_LEDGER_SECRET_ACCESS_KEY"))))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(4)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(5))).build()) {
                stage = "head-bucket";
                String bucket = required("ERASURE_LEDGER_BUCKET");
                client.headBucket(r -> r.bucket(bucket));
                stage = "list-presence-only";
                boolean any = present(client, bucket, "");
                boolean intents = present(client, bucket, "v1/production/");
                boolean catalogue = present(client, bucket, "catalogue-v1/production/");
                // Never output keys, counts, member identifiers, credentials or key fingerprints.
                System.out.println("R2_READONLY_BUCKET_ACCESS_OK");
                System.out.println("R2_OBSERVED_BUCKET_EMPTY=" + !any);
                System.out.println("R2_PRODUCTION_INTENT_PRESENT=" + intents);
                System.out.println("R2_PRODUCTION_CATALOGUE_PRESENT=" + catalogue);
                System.out.println("R2_BASELINE_ESTABLISHED=false");
                System.out.println("R2_OBJECT_CONTENTS_READ=false R2_OBJECTS_WRITTEN=false");
            }
        } catch (Exception ignored) {
            // SDK and JSON errors can contain sensitive input: suppress cause/message.
            throw new AssertionError("R2_READONLY_CHECK_FAILED stage=" + stage);
        }
    }

    private static boolean present(S3Client client, String bucket, String prefix) {
        var result = client.listObjectsV2(r -> r.bucket(bucket).prefix(prefix).maxKeys(1));
        if (result.contents().isEmpty() && Boolean.TRUE.equals(result.isTruncated()))
            throw new IllegalStateException();
        return !result.contents().isEmpty();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException();
        return value;
    }
}
