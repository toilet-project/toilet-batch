package com.geupddong.review;

import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.account.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewUnlinkJournalTest {
    static final String EPOCH="22222222-2222-2222-2222-222222222222";
    static final String A="aaaaaaaa-1111-1111-1111-111111111111",B="bbbbbbbb-1111-1111-1111-111111111111";
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"),ZoneOffset.UTC);
    static ErasureCipher cipher(int seed){byte[] key=new byte[32];Arrays.fill(key,(byte)seed);return new ErasureCipher("test",Map.of("test",Base64.getEncoder().encodeToString(key)));}
    static class Objects implements ErasureObjectStore {
        final Map<String,byte[]> data=new TreeMap<>();int puts;String failPrefix;
        public byte[] read(String key){return data.get(key);}
        public void putIfAbsent(String key,byte[] encrypted){puts++;if(failPrefix!=null&&key.startsWith(failPrefix))throw new IllegalStateException("synthetic");data.putIfAbsent(key,encrypted);}
        public List<String> list(String prefix,int maximum){var keys=data.keySet().stream().filter(k->k.startsWith(prefix)).toList();if(keys.size()>maximum)throw new IllegalStateException();return keys;}
    }
    static class Store implements CheckpointedErasureLedger.Store {
        CheckpointedErasureLedger.Head head;int writes;boolean fail,acknowledge;
        Store(){var seed=new ErasureCheckpoint(1,ReviewUnlinkRecord.REALM,EPOCH,1,0,"a".repeat(64),"",CLOCK.instant().toString());
            head=new CheckpointedErasureLedger.Head("a".repeat(40),new ErasureCheckpoint(1,seed.realm(),EPOCH,1,0,seed.inventoryDigest(Map.of()),"",seed.recordedAt()));}
        public CheckpointedErasureLedger.Head read(){if(head==null)throw new IllegalStateException();return head;}
        public void append(CheckpointedErasureLedger.Head expected,ErasureCheckpoint next){assertEquals(head,expected);writes++;
            if(!fail||acknowledge)head=new CheckpointedErasureLedger.Head(String.format("%040x",writes),next);if(fail)throw new IllegalStateException("synthetic");}
    }
    final Objects objects=new Objects();final Store store=new Store();
    ReviewUnlinkJournal journal(){return new ReviewUnlinkJournal(objects,cipher(1),store,Runnable::run,EPOCH,CLOCK);}
    @Test void encryptedIntentAndIndependentAggregateAreIdempotent(){var journal=journal();assertTrue(journal.snapshot().records().isEmpty());journal.ensureRecorded(A);journal.ensureRecorded(A);
        assertEquals(2,objects.data.size());assertEquals(1,store.writes);assertEquals(A,journal.snapshot().records().getFirst().reviewKey());
        for(byte[] value:objects.data.values())assertFalse(new String(value,StandardCharsets.UTF_8).contains(A));
        String aggregate=new String(store.head.checkpoint().bytes(),StandardCharsets.UTF_8);assertFalse(aggregate.contains(A));assertFalse(aggregate.contains("userId"));}
    @Test void missingGenesisNeverCreatesBaseline(){store.head=null;assertThrows(IllegalStateException.class,()->journal().ensureRecorded(A));assertEquals(0,objects.puts);}
    @Test void partialWriteBlocksSnapshotAndOtherRequestsButExactRetryRepairs(){objects.failPrefix="v1/";var journal=journal();assertThrows(IllegalStateException.class,()->journal.ensureRecorded(A));
        assertThrows(IllegalStateException.class,journal::snapshot);objects.failPrefix=null;assertThrows(IllegalStateException.class,()->journal.ensureRecorded(B));journal.ensureRecorded(A);assertEquals(1,journal.snapshot().records().size());}
    @Test void checkpointFailureNeverReportsSuccessAndExactRetryRecovers(){store.fail=true;var journal=journal();assertThrows(IllegalStateException.class,()->journal.ensureRecorded(A));
        assertThrows(IllegalStateException.class,journal::snapshot);store.fail=false;journal.ensureRecorded(A);assertEquals(1,journal.snapshot().records().size());}
    @Test void lostAcknowledgementDoesNotAppendTwice(){store.fail=true;store.acknowledge=true;var journal=journal();assertThrows(IllegalStateException.class,()->journal.ensureRecorded(A));
        store.fail=false;journal.ensureRecorded(A);assertEquals(1,store.writes);assertEquals(1,journal.snapshot().records().size());}
    @Test void lostBothCopiesCannotBeRebasedAsEmpty(){var journal=journal();journal.ensureRecorded(A);objects.data.clear();assertThrows(IllegalStateException.class,journal::snapshot);assertThrows(IllegalStateException.class,()->journal.ensureRecorded(B));}
    @Test void lostOneOldCopyBlocksOtherUnlinks(){var journal=journal();journal.ensureRecorded(A);objects.data.remove("v1/"+ReviewUnlinkRecord.REALM+"/"+A+".bin");assertThrows(IllegalStateException.class,journal::snapshot);assertThrows(IllegalStateException.class,()->journal.ensureRecorded(B));}
    @Test void ciphertextTamperingOrWrongKeyNeverProducesRecords(){var journal=journal();journal.ensureRecorded(A);assertThrows(IllegalStateException.class,()->new ReviewUnlinkJournal(objects,cipher(2),store,Runnable::run,EPOCH,CLOCK).snapshot());objects.data.values().iterator().next()[20]^=1;assertThrows(IllegalStateException.class,journal::snapshot);}
    @Test void wrongGenerationAndFutureCheckpointBlockBeforeWriting(){assertThrows(IllegalStateException.class,()->new ReviewUnlinkJournal(objects,cipher(1),store,Runnable::run,A,CLOCK).ensureRecorded(A));assertThrows(IllegalStateException.class,()->new ReviewUnlinkJournal(objects,cipher(1),store,Runnable::run,EPOCH,Clock.offset(CLOCK,Duration.ofSeconds(-1))).ensureRecorded(A));}
    @Test void exclusiveFailureDoesNotWrite(){assertThrows(IllegalStateException.class,()->new ReviewUnlinkJournal(objects,cipher(1),store,work->{throw new IllegalStateException();},EPOCH,CLOCK).ensureRecorded(A));assertEquals(0,objects.puts);}
}
