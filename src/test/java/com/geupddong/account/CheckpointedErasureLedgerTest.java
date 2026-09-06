package com.geupddong.account;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CheckpointedErasureLedgerTest {
    static final String EPOCH="22222222-2222-2222-2222-222222222222";
    static final Instant NOW=Instant.parse("2026-09-07T00:00:00Z");
    static ErasureRecord record(int id) {
        return new ErasureRecord(1,"production",id,"2026-01-01T00:00:00",String.format("%08d-1111-1111-1111-111111111111",id),"2026-05-01T00:00:00");
    }
    static ErasureCheckpoint checkpoint(List<ErasureRecord> records) {
        var seed=new ErasureCheckpoint(1,"production",EPOCH,1,0,"a".repeat(64),"",NOW.minusSeconds(1).toString());
        var map=new TreeMap<String,String>();
        records.forEach(r->map.put(r.objectKey(),ErasureCompletion.digest(r)));
        return new ErasureCheckpoint(1,"production",EPOCH,1,records.size(),seed.inventoryDigest(map),"",seed.recordedAt());
    }
    static class R2 implements CheckpointedErasureLedger.SnapshotLedger {
        List<ErasureRecord> catalogue=new ArrayList<>(),intents=new ArrayList<>();
        int writes;
        boolean fail;
        public void ensureRecorded(ErasureRecord record) {
            writes++;
            if (!catalogue.contains(record)) catalogue.add(record);
            if (fail) throw new IllegalStateException("PRIVATE_FIXTURE");
            if (!intents.contains(record)) intents.add(record);
        }
        public List<ErasureRecord> catalogueAtMost(int maximum) { if(catalogue.size()>maximum)throw new IllegalStateException();return List.copyOf(catalogue); }
        public List<ErasureRecord> intentsAtMost(int maximum) { if(intents.size()>maximum)throw new IllegalStateException();return List.copyOf(intents); }
    }
    static class Store implements CheckpointedErasureLedger.Store {
        CheckpointedErasureLedger.Head head;
        int writes;
        boolean fail, ambiguous;
        Store(List<ErasureRecord> records) { head=new CheckpointedErasureLedger.Head("a".repeat(40),checkpoint(records)); }
        public CheckpointedErasureLedger.Head read() { return head; }
        public void append(CheckpointedErasureLedger.Head expected,ErasureCheckpoint next) {
            writes++;
            assertEquals(head,expected);
            if (!fail || ambiguous) head=new CheckpointedErasureLedger.Head("b".repeat(40),next);
            if (fail) throw new IllegalStateException("PRIVATE_FIXTURE");
        }
    }
    static CheckpointedErasureLedger ledger(R2 r2,Store store) {
        return new CheckpointedErasureLedger(r2,store,Runnable::run,Clock.fixed(NOW,ZoneOffset.UTC),"production",EPOCH);
    }
    @Test void recordsR2AndThenAggregateCheckpoint() {
        var r2=new R2();var store=new Store(List.of());
        ledger(r2,store).ensureRecorded(record(1));
        assertEquals(1,r2.writes);assertEquals(1,store.writes);assertEquals(1,store.head.checkpoint().count());
        assertFalse(new String(store.head.checkpoint().bytes()).contains("userId"));
    }
    @Test void retryOfAcknowledgedIntentDoesNotAppendAgain() {
        var r2=new R2();var store=new Store(List.of());var ledger=ledger(r2,store);
        ledger.ensureRecorded(record(1));ledger.ensureRecorded(record(1));assertEquals(1,store.writes);
    }
    @Test void githubFailureBlocksAndExactPendingRetryRecovers() {
        var r2=new R2();var store=new Store(List.of());store.fail=true;var ledger=ledger(r2,store);
        var error=assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record(1)));
        assertEquals("ERASURE_CHECKPOINT_UNAVAILABLE",error.getMessage());
        store.fail=false;ledger.ensureRecorded(record(1));assertEquals(1,store.head.checkpoint().count());
    }
    @Test void ambiguousGitAckRetryDoesNotDuplicateCheckpoint() {
        var r2=new R2();var store=new Store(List.of());store.fail=true;store.ambiguous=true;
        var ledger=ledger(r2,store);assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record(1)));
        store.fail=false;ledger.ensureRecorded(record(1));assertEquals(1,store.writes);
    }
    @Test void partialR2CatalogueWriteRecoversOnlyForSameIntent() {
        var r2=new R2();var store=new Store(List.of());r2.fail=true;
        var ledger=ledger(r2,store);assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record(1)));
        assertEquals(0,store.writes);r2.fail=false;
        assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record(2)));
        ledger.ensureRecorded(record(1));assertEquals(1,store.writes);
    }
    @Test void lostBothR2ListsCannotBecomeNewZeroBaseline() {
        var r2=new R2();var store=new Store(List.of(record(1)));
        assertThrows(IllegalStateException.class,()->ledger(r2,store).ensureRecorded(record(2)));
        assertEquals(0,r2.writes);assertEquals(0,store.writes);
    }
    @Test void sameCountSubstitutionIsRejected() {
        var r2=new R2();r2.catalogue.add(record(3));r2.intents.add(record(3));var store=new Store(List.of(record(1)));
        assertThrows(IllegalStateException.class,()->ledger(r2,store).ensureRecorded(record(2)));assertEquals(0,r2.writes);
    }
    @Test void missingOneOldIntentIsRejected() {
        var r2=new R2();r2.catalogue.add(record(1));var store=new Store(List.of(record(1)));
        assertThrows(IllegalStateException.class,()->ledger(r2,store).ensureRecorded(record(2)));assertEquals(0,r2.writes);
    }
    @Test void generationMismatchIsRejectedBeforeWrite() {
        var r2=new R2();var store=new Store(List.of());
        var ledger=new CheckpointedErasureLedger(r2,store,Runnable::run,Clock.fixed(NOW,ZoneOffset.UTC),"production","33333333-3333-3333-3333-333333333333");
        assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record(1)));assertEquals(0,r2.writes);
    }
    @Test void lockFailureBlocksAnyLedgerAccess() {
        var r2=new R2();var store=new Store(List.of());
        var ledger=new CheckpointedErasureLedger(r2,store,work->{throw new IllegalStateException();},Clock.fixed(NOW,ZoneOffset.UTC),"production",EPOCH);
        assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record(1)));assertEquals(0,r2.writes);
    }
    @Test void missingProtectedConfigurationCannotFallBackToRawLedger() {
        var env=new MockEnvironment().withProperty("erasure.ledger.enabled","true");
        var ledger=ProtectedErasureLedgerFactory.create(env,null);
        assertThrows(IllegalStateException.class,()->ledger.ensureRecorded(record(1)));
    }
    @Test void canonicalInventoryMatchesExistingCheckpointImplementation() throws Exception {
        var records=List.of(record(2),record(1));
        var existing=com.example.toiletbatch.account.CatalogueCheckpoint.next(null,
                com.example.toiletbatch.account.ErasureCatalogueExportCli.inventory(records,"production",EPOCH),"GENESIS",NOW,true);
        assertEquals(existing.inventorySha256(),checkpoint(records).inventorySha256());
    }
}
