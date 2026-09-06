package com.geupddong.account;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Write-once intent, verified by authenticated read-back. Errors never include record/credential content. */
public final class R2ErasureLedger implements ErasureLedger {
    private final S3Client s3;
    private final ErasureCipher cipher;
    private final String bucket;
    private final String realm;
    public R2ErasureLedger(S3Client s3, ErasureCipher cipher, String bucket, String realm) {
        this.s3 = s3; this.cipher = cipher; this.bucket = bucket; this.realm = realm;
    }

    @Override public void ensureRecorded(ErasureRecord record) {
        if (!realm.equals(record.realm())) throw unavailable();
        try {
            ErasureRecord existing = readOrAbsent(record.objectKey());
            if (existing == null) {
                try {
                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(record.objectKey())
                            .ifNoneMatch("*").contentType("application/octet-stream").cacheControl("no-store")
                            .storageClass(StorageClass.STANDARD).build(), RequestBody.fromBytes(cipher.encrypt(record)));
                } catch (S3Exception race) {
                    if (race.statusCode() != 412) throw unavailable();
                }
                existing = readOrAbsent(record.objectKey());
            }
            if (!record.equals(existing)) throw unavailable();
        } catch (RuntimeException ignored) { throw unavailable(); }
    }

    private ErasureRecord readOrAbsent(String key) {
        try {
            byte[] payload = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            return cipher.decrypt(realm, key, payload);
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return null;
            throw unavailable();
        }
    }

    /** Fetch/verify the entire requested snapshot before restoring ANY database rows. */
    public List<ErasureRecord> readAll(int expectedObjects) {
        if (expectedObjects < 0 || expectedObjects > 1000000) throw unavailable();
        try {
            var records = new ArrayList<ErasureRecord>();
            var seenTokens = new java.util.HashSet<String>();
            var seenKeys = new java.util.HashSet<String>();
            String token = null;
            do {
                var page = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                        .prefix("v1/" + realm + "/").continuationToken(token).maxKeys(1000).build());
                for (var item : page.contents()) {
                    if (!seenKeys.add(item.key())) throw unavailable();
                    if (records.size() >= expectedObjects || item.size() > 8192) throw unavailable();
                    var record = readOrAbsent(item.key());
                    if (record == null) throw unavailable();
                    records.add(record);
                }
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
                if (Boolean.TRUE.equals(page.isTruncated()) && (token == null || token.isBlank())) throw unavailable();
                if (token != null && !seenTokens.add(token)) throw unavailable();
            } while (token != null);
            if (records.size() != expectedObjects) throw unavailable();
            return List.copyOf(records);
        } catch (RuntimeException ignored) { throw unavailable(); }
    }

    @Override public void close() { s3.close(); }

    /** Write-once completion; retries retain the FIRST observation, never overwrite its time. */
    public ErasureCompletion ensureCompletion(ErasureCompletion proposed) {
        try {
            if (!realm.equals(proposed.realm())) throw unavailable();
            var intent = readOrAbsent("v1/" + realm + "/" + proposed.withdrawalKey() + ".bin");
            if (intent == null || !ErasureCompletion.digest(intent).equals(proposed.intentDigest())) throw unavailable();
            var eligible = java.time.LocalDateTime.parse(intent.eligibleAt())
                    .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
            if (java.time.Instant.parse(proposed.firstConfirmedAbsentAt()).isBefore(eligible)) throw unavailable();
            var existing = readCompletion(proposed);
            if (existing == null) {
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                byte[] encrypted = cipher.encryptDocument(realm, proposed.objectKey(), json.writeValueAsBytes(proposed));
                try {
                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(proposed.objectKey())
                            .ifNoneMatch("*").contentType("application/octet-stream").cacheControl("no-store")
                            .storageClass(StorageClass.STANDARD).build(), RequestBody.fromBytes(encrypted));
                } catch (S3Exception race) {
                    if (race.statusCode() != 412) throw unavailable();
                }
                existing = readCompletion(proposed);
            }
            if (existing == null) throw unavailable();
            if (java.time.Instant.parse(existing.firstConfirmedAbsentAt()).isBefore(eligible)) throw unavailable();
            return existing;
        } catch (Exception ignored) { throw unavailable(); }
    }

    /** A future first observation or an identity/epoch mismatch fails closed. */
    public ErasureCompletion readCompletion(ErasureCompletion expected) {
        try {
            if (!realm.equals(expected.realm())) throw unavailable();
            byte[] payload;
            try {
                payload = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                        .key(expected.objectKey()).build()).asByteArray();
            } catch (S3Exception error) {
                if (error.statusCode() == 404) return null;
                throw unavailable();
            }
            var actual = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    cipher.decryptDocument(realm, expected.objectKey(), payload), ErasureCompletion.class);
            if (!actual.objectKey().equals(expected.objectKey())
                    || !actual.intentDigest().equals(expected.intentDigest())
                    || java.time.Instant.parse(actual.firstConfirmedAbsentAt())
                        .isAfter(java.time.Instant.parse(expected.firstConfirmedAbsentAt()))) throw unavailable();
            return actual;
        } catch (Exception ignored) { throw unavailable(); }
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE"); }
}
