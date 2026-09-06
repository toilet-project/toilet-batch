package com.geupddong.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
    public static R2ErasureLedger configured(Environment env) {
        try {
            String realm = env.getRequiredProperty("erasure.ledger.realm");
            String bucket = env.getRequiredProperty("erasure.ledger.bucket");
            URI endpoint = URI.create(env.getRequiredProperty("erasure.ledger.endpoint"));
            if (!realm.matches("[a-z0-9-]{3,40}") || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                    || !"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || !endpoint.getHost().matches("[a-f0-9]{32}(\\.(eu|us))?\\.r2\\.cloudflarestorage\\.com")
                    || endpoint.getUserInfo() != null || endpoint.getPort() != -1
                    || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")))
                throw new IllegalArgumentException();
            Map<String,String> keys = new ObjectMapper().readValue(
                    env.getRequiredProperty("erasure.ledger.keys-json"), new TypeReference<>() { });
            var cipher = new ErasureCipher(env.getRequiredProperty("erasure.ledger.active-key-id"), keys);
            var client = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
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
            return new R2ErasureLedger(client, cipher, bucket, realm);
        } catch (Exception ignored) { throw new IllegalStateException("ERASURE_LEDGER_CONFIGURATION_INVALID"); }
    }
}
