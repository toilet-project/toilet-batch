package com.geupddong.account;

import com.example.toiletbatch.account.ErasureCatalogueExportCli;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureCatalogueTest {
    private final ErasureRecord record = new ErasureRecord(1,"verification",1,"2000-01-01T00:00",
            "01234567-1234-1234-1234-123456789013","2000-04-01T00:00");
    private final ErasureCipher cipher = new ErasureCipher("k1", Map.of("k1", Base64.getEncoder().encodeToString(new byte[32])));
    private final Map<String, byte[]> objects = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final S3Client s3 = mock(S3Client.class);
    private final String catalogueKey = "catalogue-v1/verification/" + record.withdrawalKey() + ".bin";
    private R2ErasureLedger ledger() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> {
            String key=((GetObjectRequest)call.getArgument(0)).key();
            if (!objects.containsKey(key)) throw S3Exception.builder().statusCode(404).build();
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),objects.get(key));
        });
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest req=call.getArgument(0);RequestBody body=call.getArgument(1);
            assertEquals("*",req.ifNoneMatch());assertEquals("no-store",req.cacheControl());
            if(objects.containsKey(req.key()))throw S3Exception.builder().statusCode(412).build();
            try(var stream=body.contentStreamProvider().newStream()){objects.put(req.key(),stream.readAllBytes());}
            writes.add(req.key());return PutObjectResponse.builder().build();
        });
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call->{
            String prefix=((ListObjectsV2Request)call.getArgument(0)).prefix();
            return ListObjectsV2Response.builder().isTruncated(false).contents(objects.entrySet().stream()
                    .filter(e->e.getKey().startsWith(prefix)).map(e->S3Object.builder().key(e.getKey()).size((long)e.getValue().length).build()).toList()).build();
        });
        return new R2ErasureLedger(s3,cipher,"bucket","verification",true);
    }
    @Test void catalogueMustPrecedePrimaryIntentAndRetryDoesNotDuplicate() {
        var ledger=ledger();ledger.ensureRecorded(record);ledger.ensureRecorded(record);
        assertEquals(List.of(catalogueKey,record.objectKey()),writes);
        assertEquals(List.of(record),ledger.readCatalogue(1));assertEquals(List.of(record),ledger.readAll(1));
    }
    @Test void catalogueWriteFailurePreventsPrimaryAcknowledgement() {
        var ledger=ledger();when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("secret").build());
        var error=assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record));
        assertEquals("ERASURE_LEDGER_UNAVAILABLE",error.getMessage());assertTrue(objects.isEmpty());
    }
    @Test void primaryLossIsVisibleAgainstCatalogueAndRetryRepairsWithoutDatabaseWrites() {
        var ledger=ledger();ledger.ensureRecorded(record);objects.remove(record.objectKey());
        assertEquals(List.of(record),ledger.readCatalogue(1));assertThrows(IllegalStateException.class,()->ledger.readAll(1));
        ledger.ensureRecorded(record);assertEquals(List.of(record),ledger.readAll(1));
        assertEquals(1,writes.stream().filter(catalogueKey::equals).count());
    }
    @Test void missingOrTamperedCatalogueCannotBeAcceptedAtTrustedCount() {
        var ledger=ledger();ledger.ensureRecorded(record);objects.remove(catalogueKey);
        assertThrows(IllegalStateException.class,()->ledger.readCatalogue(1));
        ledger.ensureRecorded(record);objects.get(catalogueKey)[50]^=1;
        assertThrows(IllegalStateException.class,()->ledger.readCatalogue(1));
        assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record));
    }
    @Test void conflictingSameKeyCannotOverwriteCatalogue() {
        var ledger=ledger();ledger.ensureRecorded(record);
        var wrong=new ErasureRecord(1,record.realm(),2,record.userCreatedAt(),record.withdrawalKey(),record.eligibleAt());
        assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(wrong));assertEquals(2,writes.size());
    }
    @Test void catalogueExportIsDeterministicAndDoesNotAcceptDuplicateIdentities() throws Exception {
        String epoch="01234567-1234-1234-1234-123456789012";
        var other=new ErasureRecord(1,record.realm(),2,record.userCreatedAt(),"01234567-1234-1234-1234-123456789014",record.eligibleAt());
        var a=ErasureCatalogueExportCli.inventory(List.of(record,other),record.realm(),epoch);
        var b=ErasureCatalogueExportCli.inventory(List.of(other,record),record.realm(),epoch);
        assertArrayEquals(ErasureCatalogueExportCli.serialize(a),ErasureCatalogueExportCli.serialize(b));
        assertThrows(IllegalStateException.class,()->ErasureCatalogueExportCli.inventory(List.of(record,record),record.realm(),epoch));
    }
}
