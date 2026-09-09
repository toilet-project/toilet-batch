package com.geupddong.account;

import com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RetirementMaintenanceServiceTest {
    @TempDir Path directory;
    final Instant now=RetirementRecoveryTest.NOW;
    final GitHubRetirementRecoveryStoreTest g=new GitHubRetirementRecoveryStoreTest();
    final GitHubRetirementRecoveryStoreTest.Git git=g.new Git();
    RetirementRecoveryTest f;RetirementExecutionContext context;RetirementMaintenanceService service;
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    String operation=RetirementRecoveryTest.JOB;
    boolean missingProof;
    GitHubErasureHistoryStore history(){return new GitHubErasureHistoryStore(git,clock);}
    void setup() throws Exception {
        f=new RetirementRecoveryTest();f.directory=directory;f.setup();
        var raw=new ObjectErasureLedger(f.objects,f.cipher,"test",true);var all=raw.intentsAtMost(10);
        var cp=g.cp;var identities=new TreeMap<String,String>();all.forEach(r->identities.put(r.objectKey(),ErasureCompletion.digest(r)));
        git.replace(GitHubErasureHistoryStore.checkpointPath(1),new ErasureCheckpoint(1,"test",RetirementRecoveryTest.ID,1,2,
                cp.inventoryDigest(identities),"",cp.recordedAt()).bytes());
        for(var record:all)raw.ensureCompletion(ErasureCompletion.observed(record,RetirementRecoveryTest.ID,now.minusSeconds(33*86400)));
        context=new RetirementExecutionContext(f.objects,f.cipher,"test",RetirementRecoveryTest.ID,(record,receipt)->{
            var verified=EnumSet.allOf(Requirement.class);if(missingProof)verified.remove(Requirement.PRE_ERASURE_COPIES_AND_LOGS_REMOVED);
            return new Evidence(Instant.parse(receipt.firstConfirmedAbsentAt()),now,verified);
        },clock,()->assertTrue(f.held.get()));
        service=newService();
    }
    RetirementMaintenanceService newService() {
        return new RetirementMaintenanceService(f.journal(),history(),context,work->{
            assertTrue(f.held.compareAndSet(false,true));try{work.run();}finally{f.held.set(false);}
        },clock);
    }
    String key(long id){return "v1/test/"+new UUID(0,id)+".bin";}
    @Test void realPlannerEncryptedJournalGitHistoryCleanupAndSecondMemberWorkTogether() throws Exception {
        setup();service.prepare(operation,key(1));assertNotNull(f.journal().read(operation).recovery());
        service.resume(operation);assertThrows(IllegalStateException.class,()->history().read());
        assertEquals(List.of(operation),f.journal().operations(1));service.cleanup(operation);
        assertEquals(1,history().read().checkpoint().count());assertEquals(List.of(),f.journal().operations(0));
        String second=new UUID(0,100).toString();service.prepare(second,key(2));service.resume(second);service.cleanup(second);
        assertEquals(0,history().read().checkpoint().count());assertEquals(Map.of(),f.hashes());
        assertThrows(IllegalStateException.class,()->new GitHubErasureCheckpointStore(git).read());
    }
    @Test void missingProofStopsBeforeAnyJournalOrRemotePrepare() throws Exception {
        setup();missingProof=true;int before=git.calls.size();
        assertThrows(IllegalStateException.class,()->service.prepare(operation,key(1)));
        assertEquals(List.of(),f.journal().operations(0));assertTrue(git.calls.subList(before,git.calls.size()).stream().allMatch(c->c.startsWith("GET ")));
    }
    @Test void proofLossAfterPreparationBlocksBeforeDeletion() throws Exception {
        setup();service.prepare(operation,key(1));var before=f.hashes();missingProof=true;
        assertThrows(IllegalStateException.class,()->service.resume(operation));assertEquals(before,f.hashes());
    }
    @Test void journalCleanupFsyncFailureRecoversWithoutKeepingPersonalRecoveryFilesForever() throws Exception {
        setup();service.prepare(operation,key(1));service.resume(operation);f.safety.failSync=true;
        assertThrows(IllegalStateException.class,()->service.cleanup(operation));
        assertFalse(Files.exists(f.journalFile()));assertThrows(IllegalStateException.class,()->history().read());
        f.safety.failSync=false;newService().cleanup(operation);assertEquals(1,history().read().checkpoint().count());
    }
    @Test void cleanupLostRemoteAckIsSafeOnReopenAndCannotDeleteAnotherJob() throws Exception {
        setup();service.prepare(operation,key(1));service.resume(operation);git.loseAck=true;
        assertThrows(IllegalStateException.class,()->service.cleanup(operation));git.loseAck=false;
        newService().cleanup(operation);assertEquals(1,history().read().checkpoint().count());
        assertThrows(IllegalStateException.class,()->service.cleanup(new UUID(0,666).toString()));
    }
    @Test void cannotCleanBeforeCommitOrWithUnrelatedRemainingChanges() throws Exception {
        setup();service.prepare(operation,key(1));assertThrows(IllegalStateException.class,()->service.cleanup(operation));
        service.resume(operation);Files.delete(f.objectFile(key(2)));
        assertThrows(IllegalStateException.class,()->service.cleanup(operation));assertEquals(List.of(operation),f.journal().operations(1));
    }
    @Test void acknowledgedCleanupJournalReappearanceIsNotSilentlyDeleted() throws Exception {
        setup();service.prepare(operation,key(1));byte[] bytes=Files.readAllBytes(f.journalFile());service.resume(operation);service.cleanup(operation);
        Files.write(f.journalFile(),bytes);assertThrows(IllegalStateException.class,()->service.cleanup(operation));
        assertArrayEquals(bytes,Files.readAllBytes(f.journalFile()));
    }
    @Test void expiredJobRequiresFreshRecordedReviewThenResumesAndCleans() throws Exception {
        setup();service.prepare(operation,key(1));Instant later=now.plusSeconds(3600);var laterClock=Clock.fixed(later,ZoneOffset.UTC);
        var laterContext=new RetirementExecutionContext(f.objects,f.cipher,"test",RetirementRecoveryTest.ID,(r,c)->
                new Evidence(Instant.parse(c.firstConfirmedAbsentAt()),later,EnumSet.allOf(Requirement.class)),laterClock,()->assertTrue(f.held.get()));
        var laterHistory=new GitHubErasureHistoryStore(git,laterClock);
        var resumed=new RetirementMaintenanceService(f.journal(),laterHistory,laterContext,work->{
            assertTrue(f.held.compareAndSet(false,true));try{work.run();}finally{f.held.set(false);}
        },laterClock);
        assertThrows(IllegalStateException.class,()->resumed.resume(operation));var before=f.hashes();
        assertEquals(before,f.hashes());resumed.reviewAndResume(operation);assertEquals(1,laterHistory.inspect().reviews().size());
        resumed.cleanup(operation);assertEquals(1,laterHistory.read().checkpoint().count());assertEquals(List.of(),f.journal().operations(0));
    }
    @Test void reviewCannotRenewMissingCopyProof() throws Exception {
        setup();service.prepare(operation,key(1));missingProof=true;var before=f.hashes();
        assertThrows(IllegalStateException.class,()->service.reviewAndResume(operation));
        assertEquals(before,f.hashes());assertTrue(history().inspect().reviews().isEmpty());
    }
    @Test void orphanPrepareCanBeDiscardedOnlyWithExactUnchangedOriginals() throws Exception {
        setup();f.held.set(true);
        try{f.journal().put(context.prepare(operation,history().read(),key(1)));}finally{f.held.set(false);}
        var before=f.hashes();missingProof=true; // No deletion permission is needed to abandon a never-armed prepare.
        service.discardUnacknowledgedPrepare(operation);
        assertEquals(before,f.hashes());assertTrue(f.journal().operations(0).isEmpty());
    }
    @Test void acknowledgedPrepareCannotBeDiscardedAsOrphan() throws Exception {
        setup();service.prepare(operation,key(1));var before=f.hashes();
        assertThrows(IllegalStateException.class,()->service.discardUnacknowledgedPrepare(operation));
        assertEquals(before,f.hashes());assertEquals(List.of(operation),f.journal().operations(1));
    }
    @Test void orphanWithChangedIndependentHeadCannotBeDiscarded() throws Exception {
        setup();f.held.set(true);
        try{f.journal().put(context.prepare(operation,history().read(),key(1)));}finally{f.held.set(false);}
        git.replace("README.md","unrelated concurrent commit".getBytes());
        assertThrows(IllegalStateException.class,()->service.discardUnacknowledgedPrepare(operation));
        assertEquals(List.of(operation),f.journal().operations(1));
    }
}
