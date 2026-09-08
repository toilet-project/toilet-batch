package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated test realm + ephemeral cipher only. No Spring context, DB, Redis or GitHub writes. */
class UsR2JavaLiveTest {
    static void target(String host, String bucket, String realm) {
        if (host == null || !host.matches("[a-f0-9]{32}\\.us\\.r2\\.cloudflarestorage\\.com")
                || !"geupddong-account-erasure-ledger-us".equals(bucket)
                || realm == null || !realm.matches("verify-us-java-[a-f0-9]{20}"))
            throw new IllegalArgumentException("ISOLATION_REQUIRED");
    }
    @Test void rejectsNonUsOrProductionTargets() {
        String h="a".repeat(32)+".us.r2.cloudflarestorage.com";
        String b="geupddong-account-erasure-ledger-us", r="verify-us-java-"+"b".repeat(20);
        assertDoesNotThrow(()->target(h,b,r));
        assertThrows(IllegalArgumentException.class,()->target(h.replace(".us.","."),b,r));
        assertThrows(IllegalArgumentException.class,()->target(h,b,"production"));
        assertThrows(IllegalArgumentException.class,()->target(h,"geupddong-account-erasure-ledger",r));
        assertThrows(IllegalArgumentException.class,()->target("https://"+h,b,r));
    }
    static void check(boolean ok) { if (!ok) throw new IllegalStateException("CHECK_FAILED"); }
    static void rejects(Runnable work) {
        boolean rejected=false;
        try { work.run(); } catch (RuntimeException expected) { rejected=true; }
        check(rejected);
    }
    static String required(String key) {
        String value=System.getenv(key); check(value!=null&&!value.isBlank()); return value;
    }
    @Test
    @EnabledIfEnvironmentVariable(named="US_RUNTIME_READONLY_CHECK",matches="approved")
    void runtimeKeyCanReadBucketWithoutWrites() {
        String stage="configuration";
        try {
            String host=required("TEST_ENDPOINT_HOST"), bucket=required("TEST_BUCKET");
            target(host,bucket,"verify-us-java-"+"0".repeat(20));
            try(var s3=S3Client.builder().endpointOverride(URI.create("https://"+host)).region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("RUNTIME_ACCESS_KEY_ID"),required("RUNTIME_SECRET_ACCESS_KEY"))))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
                stage="head-bucket"; s3.headBucket(r->r.bucket(bucket));
                int observed=0; stage="list-production-presence-only";
                for(String prefix:List.of("v1/production/","catalogue-v1/production/","completion-v1/production/")) {
                    var result=s3.listObjectsV2(r->r.bucket(bucket).prefix(prefix).maxKeys(1));
                    check(!(result.contents().isEmpty()&&Boolean.TRUE.equals(result.isTruncated())));
                    if(!result.contents().isEmpty()) observed++;
                }
                System.out.println("US_RUNTIME_READONLY_PASS bucketAccess=true productionPrefixesWithObjects="+observed+" writes=false objectContentsRead=false");
            }
        } catch(Throwable ignored) {
            System.out.println("US_RUNTIME_READONLY_FAILED stage="+stage);
            throw new AssertionError("RUNTIME_READONLY_FAILED detailsSuppressed=true");
        }
    }
    static byte[] read(S3Client s3,String bucket,String key) {
        return s3.getObjectAsBytes(r->r.bucket(bucket).key(key)).asByteArray();
    }
    static boolean absent(S3Client s3,String bucket,String key) {
        try { s3.headObject(r->r.bucket(bucket).key(key)); return false; }
        catch(S3Exception e) { if(e.statusCode()!=404) throw e; return true; }
    }
    static void emptyPrefixes(S3Client s3,String bucket,String realm) {
        for(String prefix:List.of("v1/","catalogue-v1/","completion-v1/")) {
            var page=s3.listObjectsV2(r->r.bucket(bucket).prefix(prefix+realm+"/").maxKeys(1));
            check(page.contents().isEmpty()&&!Boolean.TRUE.equals(page.isTruncated()));
        }
    }
    static final class MemoryCheckpoint implements CheckpointedErasureLedger.Store {
        CheckpointedErasureLedger.Head head;
        boolean failNext;
        int appends;
        MemoryCheckpoint(ErasureCheckpoint cp) { head=new CheckpointedErasureLedger.Head("synthetic",cp); }
        public CheckpointedErasureLedger.Head read() { return head; }
        public void append(CheckpointedErasureLedger.Head expected,ErasureCheckpoint next) {
            check(expected.equals(head));
            if(failNext) { failNext=false; throw new IllegalStateException("SYNTHETIC_ACK_FAILURE"); }
            head=new CheckpointedErasureLedger.Head("synthetic-"+(++appends),next);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="US_JAVA_SYNTHETIC_CHECK",matches="approved")
    void actualFactoryRoundTripRetryAndOwnedCleanup() {
        String stage="configuration", realm="verify-us-java-"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        int passed=0,deleted=0;
        boolean success=false,clean=false;
        S3Client inspector=null;
        ErasureCipher cipher=null;
        var expected=new LinkedHashMap<String,byte[]>();
        String bucket=null;
        try {
            String host=required("TEST_ENDPOINT_HOST"); bucket=required("TEST_BUCKET");
            target(host,bucket,realm);
            final String selectedBucket=bucket;
            byte[] randomKey=new byte[32]; new SecureRandom().nextBytes(randomKey);
            var keys=Map.of("synthetic",Base64.getEncoder().encodeToString(randomKey)); Arrays.fill(randomKey,(byte)0);
            cipher=new ErasureCipher("synthetic",keys);
            var json=new ObjectMapper();
            var values=new HashMap<String,Object>();
            values.put("erasure.ledger.realm",realm); values.put("erasure.ledger.bucket",bucket);
            values.put("erasure.ledger.endpoint","https://"+host);
            values.put("erasure.ledger.access-key-id",required("TEST_ACCESS_KEY_ID"));
            values.put("erasure.ledger.secret-access-key",required("TEST_SECRET_ACCESS_KEY"));
            values.put("erasure.ledger.active-key-id","synthetic");
            values.put("erasure.ledger.keys-json",json.writeValueAsString(keys));
            values.put("erasure.ledger.catalogue-enabled",true);
            var env=new StandardEnvironment(); env.getPropertySources().addFirst(new MapPropertySource("synthetic",values));
            inspector=S3Client.builder().endpointOverride(URI.create("https://"+host)).region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("TEST_ACCESS_KEY_ID"),required("TEST_SECRET_ACCESS_KEY"))))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED).build();
            stage="empty-prefixes"; emptyPrefixes(inspector,bucket,realm); passed++;
            var first=new ErasureRecord(1,realm,9000000000001L,"2000-01-01T00:00:00",UUID.randomUUID().toString(),"2000-01-02T00:00:00");
            var second=new ErasureRecord(1,realm,9000000000002L,"2000-01-01T00:00:00",UUID.randomUUID().toString(),"2000-01-02T00:00:00");
            for(var record:List.of(first,second)) {
                expected.put(record.objectKey(),json.writeValueAsBytes(record));
                expected.put("catalogue-v1/"+realm+"/"+record.withdrawalKey()+".bin",json.writeValueAsBytes(record));
            }
            String epoch=UUID.randomUUID().toString(); Instant now=Instant.now();
            var completion=ErasureCompletion.observed(first,epoch,now);
            expected.put(completion.objectKey(),json.writeValueAsBytes(completion));
            try(var ledger=ErasureLedgerFactory.configured(env)) {
                stage="factory-roundtrip"; ledger.ensureRecorded(first);
                check(ledger.readAll(1).equals(List.of(first))&&ledger.readCatalogue(1).equals(List.of(first))); passed++;
                stage="idempotent-ciphertext";
                byte[] original=read(inspector,bucket,first.objectKey()); ledger.ensureRecorded(first);
                check(Arrays.equals(original,read(inspector,bucket,first.objectKey()))); passed++;
                stage="identity-collision";
                rejects(()->ledger.ensureRecorded(new ErasureRecord(1,first.realm(),9000000000003L,first.userCreatedAt(),first.withdrawalKey(),first.eligibleAt())));
                check(ledger.readAll(1).equals(List.of(first))&&ledger.readCatalogue(1).equals(List.of(first))); passed++;
                stage="conditional-overwrite";
                boolean precondition=false;
                try { inspector.putObject(r->r.bucket(selectedBucket).key(first.objectKey()).ifNoneMatch("*"),RequestBody.fromBytes(original)); }
                catch(S3Exception e) { if(e.statusCode()!=412) throw e; precondition=true; }
                check(precondition&&Arrays.equals(original,read(inspector,bucket,first.objectKey()))); passed++;
                stage="authenticated-cipher";
                check(cipher.decrypt(realm,first.objectKey(),original).equals(first));
                var activeCipher=cipher;
                rejects(()->activeCipher.decrypt(first.realm(),second.objectKey(),original)); passed++;
                stage="completion-first-observation";
                check(ledger.ensureCompletion(completion).equals(completion));
                check(ledger.ensureCompletion(ErasureCompletion.observed(first,epoch,now.plusSeconds(10))).equals(completion)); passed++;
                stage="checkpoint-retry";
                var seed=new ErasureCheckpoint(1,realm,epoch,1,1,"0".repeat(64),"",now.toString());
                var cp=new ErasureCheckpoint(1,realm,epoch,1,1,seed.inventoryDigest(Map.of(first.objectKey(),ErasureCompletion.digest(first))),"",now.toString());
                var store=new MemoryCheckpoint(cp); store.failNext=true;
                var protectedLedger=new CheckpointedErasureLedger(ledger,store,Runnable::run,Clock.systemUTC(),realm,epoch);
                rejects(()->protectedLedger.ensureRecorded(second));
                check(store.head.checkpoint().count()==1&&ledger.readAll(2).size()==2&&ledger.readCatalogue(2).size()==2); passed++;
                stage="checkpoint-resume"; protectedLedger.ensureRecorded(second);
                check(store.head.checkpoint().count()==2&&store.appends==1); passed++;
                stage="checkpoint-idempotence"; protectedLedger.ensureRecorded(second); check(store.appends==1); passed++;
                stage="missing-independent-checkpoint";
                var missing=new CheckpointedErasureLedger.Store() {
                    public CheckpointedErasureLedger.Head read() { throw new IllegalStateException("SYNTHETIC_MISSING"); }
                    public void append(CheckpointedErasureLedger.Head h,ErasureCheckpoint c) { throw new AssertionError(); }
                };
                var blocked=new CheckpointedErasureLedger(ledger,missing,Runnable::run,Clock.systemUTC(),realm,epoch);
                rejects(()->blocked.ensureRecorded(second)); check(ledger.readAll(2).size()==2); passed++;
            }
            success=true;
        } catch(Throwable ignored) {
            System.out.println("US_JAVA_FAILED stage="+stage);
        } finally {
            if(inspector!=null&&cipher!=null) {
                try {
                    // Delete only exact records authenticated with this invocation's ephemeral key.
                    for(var entry:expected.entrySet()) {
                        if(absent(inspector,bucket,entry.getKey())) continue;
                        byte[] payload=read(inspector,bucket,entry.getKey());
                        check(Arrays.equals(entry.getValue(),cipher.decryptDocument(realm,entry.getKey(),payload)));
                        final String b=bucket,k=entry.getKey();
                        inspector.deleteObject(r->r.bucket(b).key(k));
                        check(absent(inspector,bucket,k)); deleted++;
                    }
                    emptyPrefixes(inspector,bucket,realm); clean=true;
                } catch(Throwable ignored) { System.out.println("US_JAVA_CLEANUP_FAILED manualReview=true"); }
                inspector.close();
            }
            // Synthetic realm is a non-personal cleanup receipt, never a credential or member ID.
            System.out.println("US_JAVA_RESULT checks="+passed+" passed="+success+" cleanup="+clean+" deletedSyntheticObjects="+deleted+" realm="+realm);
            System.out.println("US_JAVA_BOUNDARY productionWrites=false db=false redis=false githubWrites=false ephemeralCipher=true checkpointStore=inMemory");
        }
        if(!success||!clean) throw new AssertionError("US_JAVA_VALIDATION_FAILED detailsSuppressed=true");
    }
}
