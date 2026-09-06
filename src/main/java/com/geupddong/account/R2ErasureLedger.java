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
    private static IllegalStateException unavailable() { return new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE"); }
}
