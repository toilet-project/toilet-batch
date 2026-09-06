package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ErasureIntentInventoryTest {
    @TempDir Path directory;
    private static final String EPOCH="01234567-1234-1234-1234-123456789012";
    private final ErasureRecord intent=new ErasureRecord(1,"verification",1,"2000-01-01T00:00",
            "01234567-1234-1234-1234-123456789013","2000-04-01T00:00");
    private ErasureIntentInventory manifest(){return new ErasureIntentInventory(1,intent.realm(),EPOCH,Map.of(intent.objectKey(),ErasureCompletion.digest(intent)));}
    private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void exactIndependentManifestPassesAndMissingExtraOrChangedIntentFails(){
        var inventory=manifest();assertDoesNotThrow(()->inventory.verify(List.of(intent)));
        assertThrows(IllegalStateException.class,()->inventory.verify(List.of()));
        assertThrows(IllegalStateException.class,()->inventory.verify(List.of(intent,intent)));
        var changed=new ErasureRecord(1,intent.realm(),2,intent.userCreatedAt(),intent.withdrawalKey(),intent.eligibleAt());
        assertThrows(IllegalStateException.class,()->inventory.verify(List.of(changed)));
    }
    @Test void manifestChecksumMustMatchItsExactBytes()throws Exception{
        var file=directory.resolve("inventory.json");byte[] bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(manifest());
        Files.write(file,bytes);assertEquals(manifest(),ErasureIntentInventory.read(file,hash(bytes)));
        assertThrows(IllegalStateException.class,()->ErasureIntentInventory.read(file,"0".repeat(64)));
    }
    @Test void duplicateJsonFieldsAreRejectedEvenWithMatchingHash()throws Exception{
        byte[] bytes=("{\"version\":1,\"version\":1,\"realm\":\"verification\",\"databaseEpoch\":\""+EPOCH+"\",\"intentDigests\":{}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file=directory.resolve("duplicate.json");Files.write(file,bytes);
        assertThrows(IllegalStateException.class,()->ErasureIntentInventory.read(file,hash(bytes)));
    }
    @Test void wrongRealmAndDuplicateUserIdsDoNotPass(){
        assertThrows(RuntimeException.class,()->new ErasureIntentInventory(1,"other",EPOCH,manifest().intentDigests()));
        var other=new ErasureRecord(1,intent.realm(),1,intent.userCreatedAt(),"01234567-1234-1234-1234-123456789014",intent.eligibleAt());
        var inventory=new ErasureIntentInventory(1,intent.realm(),EPOCH,Map.of(intent.objectKey(),ErasureCompletion.digest(intent),other.objectKey(),ErasureCompletion.digest(other)));
        assertThrows(IllegalStateException.class,()->inventory.verify(List.of(intent,other)));
    }
}
