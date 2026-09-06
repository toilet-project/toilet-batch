package com.example.toiletbatch.account;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueCheckpointTest {
    final String id="01234567-1234-1234-1234-123456789012";
    final Instant now=Instant.parse("2026-09-06T12:00:00Z");
    ErasureIntentInventory inventory(){return new ErasureIntentInventory(1,"verification",id,Map.of("v1/verification/"+id+".bin","a".repeat(64)));}
    CatalogueCheckpoint first(){return CatalogueCheckpoint.next(null,inventory(),"GENESIS",now,true);}
    @Test void canonicalAggregateChainContainsNoMemberList(){
        var first=first();var next=CatalogueCheckpoint.next(first,inventory(),first.digest(),now.plusSeconds(1),true);
        assertEquals(2,next.sequence());assertEquals(first.digest(),next.previousCheckpointSha256());assertEquals(1,next.count());
        String json=new String(next.bytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains(".bin"));assertFalse(json.contains("intentDigests"));assertFalse(json.endsWith("\n"));
    }
    @Test void explicitMembershipReviewAndGenesisRequired(){
        assertThrows(IllegalStateException.class,()->CatalogueCheckpoint.next(null,inventory(),"GENESIS",now,false));
        assertThrows(IllegalStateException.class,()->CatalogueCheckpoint.next(null,inventory(),"",now,true));
    }
    @Test void decreaseWrongParentEpochChangeAndReversedTimeRejected(){
        var first=first();
        assertThrows(IllegalStateException.class,()->CatalogueCheckpoint.next(first,new ErasureIntentInventory(1,"verification",id,Map.of()),first.digest(),now,true));
        assertThrows(IllegalStateException.class,()->CatalogueCheckpoint.next(first,inventory(),"0".repeat(64),now,true));
        var changed=new ErasureIntentInventory(1,"verification","01234567-1234-1234-1234-123456789013",inventory().intentDigests());
        assertThrows(IllegalStateException.class,()->CatalogueCheckpoint.next(first,changed,first.digest(),now,true));
        assertThrows(IllegalStateException.class,()->CatalogueCheckpoint.next(first,inventory(),first.digest(),now.minusSeconds(1),true));
    }
}
