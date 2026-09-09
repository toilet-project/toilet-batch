package com.geupddong.account;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;

/** Initial zero-history preparation ONLY. Read-only and no Spring application/DB initialization.
 * Any real record requires the separately reviewed migration procedure, never automatic bootstrap.
 */
public final class LocalLedgerEmptyPreflight {
    private static String env(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank()) throw new IllegalStateException();
        return value;
    }
    private static void require(boolean ok) { if(!ok) throw new IllegalStateException(); }
    public static void main(String[] args) {
        try {
            require(args.length==0 && env("LOCAL_PREFLIGHT_READONLY").equals("approved"));
            Path root=Path.of("/home/luha/geupddong-erasure-ledger");
            require(((Number)Files.getAttribute(root,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()==1000);
            require(((Number)Files.getAttribute(root,"unix:gid",LinkOption.NOFOLLOW_LINKS)).intValue()==1000);
            var local=new FileErasureObjectStore(root,"production",env("LOCAL_STORE_ID"));
            String endpoint=env("SOURCE_ENDPOINT");
            require(endpoint.matches("https://[a-f0-9]{32}\\.us\\.r2\\.cloudflarestorage\\.com"));
            var checkpoints=GitHubErasureCheckpointStore.configured(env("CHECKPOINT_TOKEN"));
            var head=checkpoints.read(); var cp=head.checkpoint();
            require(cp.realm().equals("production") && cp.databaseEpoch().equals(env("DATABASE_EPOCH")));
            require(cp.count()==0 && cp.inventorySha256().equals(cp.inventoryDigest(Map.of())));
            require(!Instant.parse(cp.recordedAt()).isAfter(Instant.now()));
            try(var source=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(env("SOURCE_ID"),env("SOURCE_SECRET"))))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
                for(int repeat=0;repeat<2;repeat++) {
                    for(String kind:List.of("v1","catalogue-v1","completion-v1")) {
                        String prefix=kind+"/production/";
                        var page=source.listObjectsV2(r->r.bucket("geupddong-account-erasure-ledger-us").prefix(prefix).maxKeys(1));
                        require(page.contents().isEmpty()&&!Boolean.TRUE.equals(page.isTruncated()));
                        require(local.list(prefix,0).isEmpty());
                    }
                    require(head.equals(checkpoints.read()));
                }
            }
            System.out.println("LOCAL_EMPTY_PREFLIGHT_PASS records=0 checkpointMatched=true directoryVerified=true activationAllowed=false");
        } catch(Throwable ignored) {
            System.err.println("LOCAL_EMPTY_PREFLIGHT_FAILED detailsSuppressed=true"); System.exit(1);
        }
    }
}
