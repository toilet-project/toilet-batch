package com.geupddong.account;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureCompletionTest {
    private static final String EPOCH = "01234567-1234-1234-1234-123456789012";
    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private final ErasureRecord intent = new ErasureRecord(1,"verification",42,"2000-01-01T00:00",
            "01234567-1234-1234-1234-123456789013","2000-04-01T00:00");
    private final ErasureCipher cipher = new ErasureCipher("k1",Map.of("k1",Base64.getEncoder().encodeToString(new byte[32])));
    private final Map<String,byte[]> objects = new HashMap<>();
    private final S3Client s3 = mock(S3Client.class);
    private final AtomicBoolean lostAck = new AtomicBoolean();

    private R2ErasureLedger ledger() {
        objects.put(intent.objectKey(),cipher.encrypt(intent));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> {
            String key = ((GetObjectRequest)call.getArgument(0)).key();
            if (!objects.containsKey(key)) throw S3Exception.builder().statusCode(404).build();
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), objects.get(key));
        });
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest req=call.getArgument(0); RequestBody body=call.getArgument(1);
            assertEquals("*",req.ifNoneMatch());
            assertEquals("no-store",req.cacheControl());
            if(objects.containsKey(req.key())) throw S3Exception.builder().statusCode(412).build();
            try(var stream=body.contentStreamProvider().newStream()) { objects.put(req.key(),stream.readAllBytes()); }
            if(lostAck.getAndSet(false)) throw S3Exception.builder().statusCode(504).message("secret").build();
            return PutObjectResponse.builder().build();
        });
        return new R2ErasureLedger(s3,cipher,"bucket","verification");
    }

    @Test void completionIsEncryptedInSeparatePrefixAndRetainsFirstObservation() {
        var ledger=ledger(); var first=ErasureCompletion.observed(intent,EPOCH,NOW);
        assertEquals(first,ledger.ensureCompletion(first));
        assertEquals(first,ledger.ensureCompletion(ErasureCompletion.observed(intent,EPOCH,NOW.plusSeconds(600))));
        assertTrue(first.objectKey().startsWith("completion-v1/"));
        assertFalse(new String(objects.get(first.objectKey()),java.nio.charset.StandardCharsets.UTF_8).contains(EPOCH));
        verify(s3,times(1)).putObject(any(PutObjectRequest.class),any(RequestBody.class));
        assertEquals(intent,cipher.decrypt(intent.realm(),intent.objectKey(),objects.get(intent.objectKey())));
    }
    @Test void lostAcknowledgementIsRetryableWithoutChangingFirstTime() {
        var ledger=ledger(); var first=ErasureCompletion.observed(intent,EPOCH,NOW); lostAck.set(true);
        var error=assertThrows(IllegalStateException.class,()->ledger.ensureCompletion(first));
        assertEquals("ERASURE_LEDGER_UNAVAILABLE",error.getMessage()); assertNull(error.getCause());
        assertEquals(first,ledger.ensureCompletion(ErasureCompletion.observed(intent,EPOCH,NOW.plusSeconds(30))));
    }
    @Test void missingIntentCannotCreateCompletion() {
        var ledger=ledger();objects.clear();
        assertThrows(IllegalStateException.class,()->ledger.ensureCompletion(ErasureCompletion.observed(intent,EPOCH,NOW)));
        verify(s3,never()).putObject(any(PutObjectRequest.class),any(RequestBody.class));
    }
    @Test void alteredIntentIdentityCannotReuseCompletion() {
        var ledger=ledger(); var first=ErasureCompletion.observed(intent,EPOCH,NOW); ledger.ensureCompletion(first);
        var changed=new ErasureRecord(1,intent.realm(),43,intent.userCreatedAt(),intent.withdrawalKey(),intent.eligibleAt());
        assertThrows(IllegalStateException.class,()->ledger.readCompletion(ErasureCompletion.observed(changed,EPOCH,NOW)));
    }
    @Test void tamperingFutureTimeAndDifferentEpochDoNotPass() {
        var ledger=ledger();var first=ErasureCompletion.observed(intent,EPOCH,NOW);ledger.ensureCompletion(first);
        assertThrows(IllegalStateException.class,()->ledger.readCompletion(ErasureCompletion.observed(intent,EPOCH,NOW.minusSeconds(1))));
        var next=ErasureCompletion.observed(intent,"01234567-1234-1234-1234-123456789014",NOW);
        assertNull(ledger.readCompletion(next));
        objects.put(next.objectKey(),objects.get(first.objectKey()));
        assertThrows(IllegalStateException.class,()->ledger.readCompletion(next));
        objects.get(first.objectKey())[50]^=1;
        assertThrows(IllegalStateException.class,()->ledger.readCompletion(first));
    }
    @Test void cryptoRejectsCrossDocumentTypeAndWrongKeys() throws Exception {
        var completion=ErasureCompletion.observed(intent,EPOCH,NOW);
        byte[] bytes=cipher.encryptDocument(completion.realm(),completion.objectKey(),new ObjectMapper().writeValueAsBytes(completion));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(intent.realm(),intent.objectKey(),bytes));
        assertThrows(IllegalStateException.class,()->cipher.decryptDocument("other",completion.objectKey(),bytes));
        var rotated=new ErasureCipher("k2",Map.of("k2",Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalStateException.class,()->rotated.decryptDocument(completion.realm(),completion.objectKey(),bytes));
    }
    @Test void validationAndDigestIncludeEveryIdentityField() {
        assertThrows(IllegalArgumentException.class,()->ErasureCompletion.observed(intent,"invalid",NOW));
        assertThrows(IllegalArgumentException.class,()->new ErasureCompletion(1,intent.realm(),intent.withdrawalKey(),
                ErasureCompletion.digest(intent),EPOCH,"2026-09-06T21:00:00+09:00"));
        var changed=new ErasureRecord(1,intent.realm(),intent.userId(),intent.userCreatedAt(),intent.withdrawalKey(),"2000-04-02T00:00");
        assertNotEquals(ErasureCompletion.digest(intent),ErasureCompletion.digest(changed));
        assertThrows(IllegalStateException.class,()->cipher.encryptDocument(intent.realm(),intent.objectKey(),new byte[8001]));
    }
}
