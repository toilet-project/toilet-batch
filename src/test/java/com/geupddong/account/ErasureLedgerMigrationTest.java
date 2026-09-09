package com.geupddong.account;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ErasureLedgerMigrationTest {
    static final String REALM="verification", EPOCH="01234567-1234-1234-1234-123456789012";
    static final Instant NOW=Instant.parse("2026-09-09T00:00:00Z");
    static class Memory implements ErasureObjectStore {
        final Map<String,byte[]> data=new TreeMap<>(); int writes; int failAt=Integer.MAX_VALUE;
        public byte[] read(String k) { return data.get(k); }
        public void putIfAbsent(String k,byte[] v) {
            if (++writes==failAt) throw new IllegalStateException("private error");
            data.putIfAbsent(k,v.clone());
        }
        public List<String> list(String p,int max) { return data.keySet().stream().filter(k->k.startsWith(p)).toList(); }
    }
    final Memory source=new Memory(), target=new Memory();
    final ErasureCipher cipher=new ErasureCipher("test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
    final ErasureRecord record=new ErasureRecord(1, REALM, 42,"2000-01-01T00:00",new UUID(0,42).toString(),"2000-04-01T00:00");
    final ObjectErasureLedger ledger=new ObjectErasureLedger(source,cipher,REALM,true);
    CheckpointedErasureLedger.Head head;
    int reads, changeAt=Integer.MAX_VALUE;
    final CheckpointedErasureLedger.Store checkpoints=new CheckpointedErasureLedger.Store() {
        public CheckpointedErasureLedger.Head read() {
            if (++reads >= changeAt) return new CheckpointedErasureLedger.Head("changed",head.checkpoint());
            return head;
        }
        public void append(CheckpointedErasureLedger.Head h, ErasureCheckpoint cp) { fail("no independent writes"); }
    };
    void setup(boolean hasRecord) {
        var seed=new ErasureCheckpoint(1,REALM,EPOCH,1,0,"0".repeat(64),"",NOW.toString());
        Map<String,String> inventory=hasRecord ? Map.of(record.objectKey(),ErasureCompletion.digest(record)) : Map.of();
        head=new CheckpointedErasureLedger.Head("revision",new ErasureCheckpoint(1,REALM,EPOCH,1,inventory.size(),seed.inventoryDigest(inventory),"",NOW.toString()));
        if(hasRecord) ledger.ensureRecorded(record);
    }
    ErasureLedgerMigration migration() { return new ErasureLedgerMigration(source,target,cipher,checkpoints,REALM,EPOCH,Clock.fixed(NOW,ZoneOffset.UTC)); }
    void denied(Runnable action) {
        var error=assertThrows(IllegalStateException.class,action::run);
        assertEquals("ERASURE_LOCAL_MIGRATION_UNVERIFIED",error.getMessage()); assertNull(error.getCause());
    }
    @Test void dryRunNeverWritesAndCopyPreservesCiphertextForAllCompletionEpochs() {
        setup(true);
        ledger.ensureCompletion(ErasureCompletion.observed(record,EPOCH,NOW.minusSeconds(100)));
        ledger.ensureCompletion(ErasureCompletion.observed(record,new UUID(0,9).toString(),NOW.minusSeconds(50)));
        int before=source.writes;
        var plan=migration().dryRun(); assertFalse(plan.applied()); assertEquals(4,plan.pendingObjects());
        assertEquals(2,plan.completions()); assertEquals(0,target.writes);
        var copied=migration().copyApproved(plan.ciphertextInventoryHash(),true);
        assertTrue(copied.applied()); assertEquals(0,copied.pendingObjects());
        assertEquals(source.data.keySet(),target.data.keySet()); source.data.forEach((k,v)->assertArrayEquals(v,target.read(k)));
        assertEquals(before,source.writes);
    }
    @Test void emptyRequiresExistingIndependentZeroCheckpoint() {
        setup(false); assertEquals(0,migration().dryRun().pendingObjects());
        setup(true); source.data.clear(); denied(()->migration().dryRun());
    }
    @Test void requiresExplicitFrozenWritersAndExactDryRunDigest() {
        setup(true); var plan=migration().dryRun();
        denied(()->migration().copyApproved(plan.ciphertextInventoryHash(),false));
        denied(()->migration().copyApproved("0".repeat(64),true)); assertEquals(0,target.writes);
    }
    @Test void partialWriteCanResumeWithoutReEncryptionOrSourceDeletion() {
        setup(true); var plan=migration().dryRun(); target.failAt=2;
        denied(()->migration().copyApproved(plan.ciphertextInventoryHash(),true));
        assertEquals(1,target.data.size()); assertEquals(1,migration().dryRun().pendingObjects());
        target.failAt=Integer.MAX_VALUE; migration().copyApproved(plan.ciphertextInventoryHash(),true);
        source.data.forEach((k,v)->assertArrayEquals(v,target.read(k)));
    }
    @Test void differentDestinationBytesAreNeverOverwritten() {
        setup(true); byte[] conflicting=cipher.encrypt(new ErasureRecord(1,REALM,99,record.userCreatedAt(),record.withdrawalKey(),record.eligibleAt()));
        target.data.put(record.objectKey(),conflicting); denied(()->migration().dryRun());
        assertArrayEquals(conflicting,target.read(record.objectKey())); assertEquals(0,target.writes);
    }
    @Test void extraDestinationObjectBlocksCopy() {
        setup(true); target.data.put("v1/verification/"+new UUID(0,3)+".bin",cipher.encrypt(record));
        denied(()->migration().dryRun()); assertEquals(0,target.writes);
    }
    @Test void missingCatalogueAndCorruptionBlockCopy() {
        setup(true); source.data.values().iterator().next()[35]^=1; denied(()->migration().dryRun());
        source.data.clear(); ledger.ensureRecorded(record); source.data.keySet().removeIf(k->k.startsWith("catalogue"));
        denied(()->migration().dryRun());
    }
    @Test void orphanCompletionAndWrongReceiptIdentityAreRejected() throws Exception {
        setup(true);
        var orphan=new ErasureCompletion(1,REALM,new UUID(0,99).toString(),ErasureCompletion.digest(record),EPOCH,NOW.toString());
        source.data.put(orphan.objectKey(),cipher.encryptDocument(REALM,orphan.objectKey(),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(orphan)));
        denied(()->migration().dryRun());
    }
    @Test void independentHeadChangeBlocksBeforeAnyWrite() {
        setup(true); changeAt=2; denied(()->migration().dryRun()); assertEquals(0,target.writes);
    }
    @Test void independentlyConfirmedContentChangeInvalidatesEarlierPlan() {
        setup(true); var plan=migration().dryRun();
        ledger.ensureCompletion(ErasureCompletion.observed(record,EPOCH,NOW));
        denied(()->migration().copyApproved(plan.ciphertextInventoryHash(),true)); assertEquals(0,target.writes);
    }
}
