package com.geupddong.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;

public final class ErasureLedgerFactory {
    private ErasureLedgerFactory() { }
    public static ErasureLedger create(Environment env) {
        if (!env.getProperty("erasure.ledger.enabled", Boolean.class, false))
            return record -> { throw new IllegalStateException("ERASURE_LEDGER_NOT_CONFIGURED"); };
        return configured(env);
    }
    public static ObjectErasureLedger configured(Environment env) {
        try {
            String realm = env.getRequiredProperty("erasure.ledger.realm");
            if (!realm.matches("[a-z0-9-]{3,40}")) throw new IllegalArgumentException();
            if ("LOCAL".equals(env.getProperty("erasure.ledger.provider", "R2"))) {
                if (!env.getProperty("erasure.ledger.local-acceptance-verified", Boolean.class, false)
                        || !env.getProperty("erasure.ledger.catalogue-enabled", Boolean.class, false))
                    throw new IllegalArgumentException();
                Map<String,String> localKeys = new ObjectMapper().readValue(
                        env.getRequiredProperty("erasure.ledger.keys-json"), new TypeReference<>() { });
                var localCipher = new ErasureCipher(env.getRequiredProperty("erasure.ledger.active-key-id"), localKeys);
                return new ObjectErasureLedger(new FileErasureObjectStore(
                        java.nio.file.Path.of(env.getRequiredProperty("erasure.ledger.local-directory")), realm,
                        env.getRequiredProperty("erasure.ledger.local-store-id")), localCipher, realm, true);
            }
            String bucket = env.getRequiredProperty("erasure.ledger.bucket");
            var storage = ErasureStorageEndpoint.resolve(
                    env.getProperty("erasure.ledger.provider", "R2"),
                    env.getRequiredProperty("erasure.ledger.endpoint"),
                    env.getProperty("erasure.ledger.domestic-acceptance-verified", Boolean.class, false));
            if (!realm.matches("[a-z0-9-]{3,40}") || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
                throw new IllegalArgumentException();
            Map<String,String> keys = new ObjectMapper().readValue(
                    env.getRequiredProperty("erasure.ledger.keys-json"), new TypeReference<>() { });
            var cipher = new ErasureCipher(env.getRequiredProperty("erasure.ledger.active-key-id"), keys);
            var client = S3Client.builder().endpointOverride(storage.endpoint()).region(Region.of(storage.signingRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            env.getRequiredProperty("erasure.ledger.access-key-id"),
                            env.getRequiredProperty("erasure.ledger.secret-access-key"))))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(4)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                    .build();
            return new R2ErasureLedger(client, cipher, bucket, realm,
                    env.getProperty("erasure.ledger.catalogue-enabled", Boolean.class, false));
        } catch (Exception ignored) { throw new IllegalStateException("ERASURE_LEDGER_CONFIGURATION_INVALID"); }
    }
}
