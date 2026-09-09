package com.geupddong.account;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Legacy transport; local selection never constructs this client. */
final class S3ErasureObjectStore implements ErasureObjectStore {
    private final S3Client client;
    private final String bucket;
    S3ErasureObjectStore(S3Client client, String bucket) { this.client = client; this.bucket = bucket; }
    @Override public byte[] read(String key) {
        try {
            byte[] bytes = client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            if (bytes.length > 8192) throw unavailable();
            return bytes;
        } catch (S3Exception e) { if (e.statusCode() == 404) return null; throw unavailable(); }
    }
    @Override public void putIfAbsent(String key, byte[] encrypted) {
        // Preserve the existing read-first retry behaviour and conditional writes.
        if (read(key) != null) return;
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*")
                    .contentType("application/octet-stream").cacheControl("no-store").storageClass(StorageClass.STANDARD).build(),
                    RequestBody.fromBytes(encrypted));
        } catch (S3Exception race) { if (race.statusCode() != 412) throw unavailable(); }
    }
    @Override public List<String> list(String prefix, int maximum) {
        if (maximum < 0 || maximum > 1000000) throw unavailable();
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        var tokens = new HashSet<String>();
        String token = null;
        do {
            var page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                    .continuationToken(token).maxKeys(1000).build());
            for (var object : page.contents()) {
                if (result.size() >= maximum || object.size() > 8192 || !seen.add(object.key())) throw unavailable();
                result.add(object.key());
            }
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            if (Boolean.TRUE.equals(page.isTruncated()) && (token == null || token.isBlank())) throw unavailable();
            if (token != null && !tokens.add(token)) throw unavailable();
        } while (token != null);
        return List.copyOf(result);
    }
    @Override public void close() { client.close(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE"); }
}
