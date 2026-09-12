package com.example.toiletbatch.account;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geupddong.account.ErasureCheckpoint;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewUnlinkLedgerPreflightCliTest {
    @Test void genesisIsCanonicalEmptyReviewInventory() throws Exception {
        String epoch="22222222-2222-4222-8222-222222222222";
        Instant at=Instant.parse("2026-09-12T05:00:00Z");
        var checkpoint=ReviewUnlinkLedgerPreflightCli.genesis(epoch,at);
        assertEquals("review-anonymization",checkpoint.realm());
        assertEquals(epoch,checkpoint.databaseEpoch());
        assertEquals(1,checkpoint.sequence());
        assertEquals(0,checkpoint.count());
        assertEquals("",checkpoint.previousCheckpointSha256());
        assertEquals(checkpoint.inventoryDigest(Map.of()),checkpoint.inventorySha256());
        assertArrayEquals(checkpoint.bytes(),new ObjectMapper().readValue(checkpoint.bytes(),ErasureCheckpoint.class).bytes());
    }
}
