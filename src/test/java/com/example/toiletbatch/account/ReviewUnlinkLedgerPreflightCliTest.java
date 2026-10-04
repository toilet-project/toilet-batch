package com.example.toiletbatch.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geupddong.account.ErasureCheckpoint;
import com.geupddong.account.CheckpointedErasureLedger;
import com.geupddong.review.ReviewUnlinkJournal;
import com.geupddong.review.ReviewUnlinkRecord;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewUnlinkLedgerPreflightCliTest {
    @Test void snapshotProofOnlyExposesVerifiedAggregateHashesAndCount() throws Exception {
        var snapshot=mock(ReviewUnlinkJournal.Snapshot.class);
        var checkpoint=ReviewUnlinkLedgerPreflightCli.genesis("22222222-2222-4222-8222-222222222222",Instant.parse("2026-09-12T05:00:00Z"));
        when(snapshot.head()).thenReturn(new CheckpointedErasureLedger.Head("private-revision",checkpoint));
        when(snapshot.records()).thenReturn(List.of(new ReviewUnlinkRecord(1,ReviewUnlinkRecord.REALM,"33333333-3333-4333-8333-333333333333")));
        var proof=ReviewUnlinkLedgerPreflightCli.snapshotProof(snapshot);
        assertEquals(Set.of("outcome","records","checkpointSha256","inventorySha256"),proof.keySet());
        assertEquals(1,proof.get("records"));
        assertEquals(checkpoint.digest(),proof.get("checkpointSha256"));
        String output=new ObjectMapper().writeValueAsString(proof);
        assertFalse(output.contains("private-revision"));
        assertFalse(output.contains("33333333-3333-4333-8333-333333333333"));
        assertFalse(output.contains("22222222-2222-4222-8222-222222222222"));
    }

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
