package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.account.RetirementRecoveryCoordinator.State;
import com.geupddong.account.RetirementRecoveryJournal.Entry;
import com.geupddong.account.RetirementRecoveryJournal.Target;

/** Real synthetic encrypted files and reopened on-disk fake independent server.
 * Not a GitHub integration test, power-loss test, or a production eligibility collector. */
class RetirementRecoveryTest {
    static final String REALM="test", ID="00000000-0000-0000-0000-000000000001";
    static final String JOB="00000000-0000-0000-0000-000000000099";
    static final Instant NOW=Instant.parse("2026-10-10T00:00:00Z");
    @TempDir Path directory;
    final FileErasureObjectStoreTest.TestSafety safety=new FileErasureObjectStoreTest.TestSafety();
    final ErasureCipher cipher=new ErasureCipher("test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
    final AtomicBoolean held=new AtomicBoolean();
    Path journalRoot,objectRoot,statePath;
    FileErasureObjectStore objects;
    Entry entry;
    boolean contextFailure,leaseFailure;
    int deleteCalls;

    class Server implements RetirementRecoveryCoordinator.Independent {
        int failAt=-1; boolean failAfter; boolean failPrepare;
        State readFile() {
            try {return Files.exists(statePath)?new ObjectMapper().readValue(Files.readAllBytes(statePath),State.class):null;}
            catch(Exception e) {throw new IllegalStateException();}
        }
        void save(State state) {
            try {Files.write(statePath,new ObjectMapper().writeValueAsBytes(state));}
            catch(Exception e) {throw new IllegalStateException();}
        }
        public State read() {assertTrue(held.get());return readFile();}
        public void prepare(Entry request) {
            assertNull(read()); save(new State("revision-0",request.digest(),0));
            if(failPrepare) throw new IllegalStateException("lost private acknowledgement");
        }
        public void advance(State expected,Entry request,int phase) {
            assertEquals(expected,read()); assertEquals(expected.phase()+1,phase);
            assertEquals(request.digest(),expected.journalDigest());
            if(failAt==phase && !failAfter) throw new IllegalStateException("private failure");
            save(new State("revision-"+phase,request.digest(),phase));
            if(failAt==phase) throw new IllegalStateException("private lost acknowledgement");
        }
    }
    RetirementRecoveryJournal journal() {
        return new RetirementRecoveryJournal(journalRoot,REALM,ID,cipher,safety);
    }
    Map<String,String> hashes() {
        var hashes=new TreeMap<String,String>();
        for(String prefix:List.of("v1/","catalogue-v1/","completion-v1/"))
            for(String key:objects.list(prefix+REALM+"/",100)) hashes.put(key,ErasureCheckpoint.hash(objects.read(key)));
        return hashes;
    }
    RetirementRecoveryCoordinator coordinator(Server server,Instant time) {
        return new RetirementRecoveryCoordinator(journal(),server,new RetirementRecoveryCoordinator.Objects() {
            public Map<String,String> inventory() {assertTrue(held.get()); return hashes();}
            public void removeExact(String key,String hash,boolean retry) {
                assertTrue(held.get()); assertEquals(1,server.read().phase()%2);
                deleteCalls++; objects.removeExact(key,hash,retry);
            }
        },(job,state)->{assertTrue(held.get());if(contextFailure) throw new IllegalStateException();},work->{
            if(leaseFailure || !held.compareAndSet(false,true)) throw new IllegalStateException();
            try {work.run();} finally {held.set(false);}
        },Clock.fixed(time,ZoneOffset.UTC));
    }
    void setup() throws Exception {
        journalRoot=Files.createDirectory(directory.resolve("journal"));
        objectRoot=Files.createDirectory(directory.resolve("objects")); statePath=directory.resolve("independent.json");
        Files.write(journalRoot.resolve(".retirement-store"),RetirementRecoveryJournal.marker(REALM,ID));
        Files.createFile(journalRoot.resolve(".retirement.lock"));
        Files.write(objectRoot.resolve(".ledger-store"),FileErasureObjectStore.markerBytes(REALM,ID));
        Files.createFile(objectRoot.resolve(".ledger.lock"));
        objects=new FileErasureObjectStore(objectRoot,REALM,ID,safety);
        var target=new ErasureRecord(1,REALM,1,"2026-01-01T00:00",ID,"2026-04-01T00:00");
        var other=new ErasureRecord(1,REALM,2,"2026-01-01T00:00",new UUID(0,2).toString(),"2026-04-01T00:00");
        var ledger=new ObjectErasureLedger(objects,cipher,REALM,true);
        ledger.ensureRecorded(target); ledger.ensureRecorded(other);
        var completion=ErasureCompletion.observed(target,ID,NOW.minusSeconds(33*86400)); ledger.ensureCompletion(completion);
        var all=hashes();var targets=new ArrayList<Target>();
        for(String key:List.of(target.objectKey(),"catalogue-v1/"+REALM+"/"+ID+".bin",completion.objectKey()))
            targets.add(new Target(key,all.remove(key)));
        entry=new Entry(1,REALM,ID,JOB,NOW.toString(),NOW.plusSeconds(600).toString(),"a".repeat(64),
                "original-revision","b".repeat(64),2,"c".repeat(64),RetirementRecoveryCoordinator.inventoryDigest(all),targets);
    }
    void blocked(Runnable action) {
        var ex=assertThrows(IllegalStateException.class,action::run);
        assertEquals("RETIREMENT_RECOVERY_BLOCKED",ex.getMessage()); assertNull(ex.getCause()); assertFalse(held.get());
    }
    Path journalFile() {return journalRoot.resolve("retirement-"+JOB+".bin");}
    Path objectFile(String key) {return objectRoot.resolve(key.replace("/","__"));}

    @Test void encryptedJournalReopensAndRetryPreservesOriginalCiphertext() throws Exception {
        setup(); journal().put(entry); byte[] original=Files.readAllBytes(journalFile());
        assertFalse(new String(original,java.nio.charset.StandardCharsets.UTF_8).contains(ID));
        assertEquals(entry,journal().read(JOB)); journal().put(entry);
        assertArrayEquals(original,Files.readAllBytes(journalFile()));
        assertFalse(entry.toString().contains(ID)); assertFalse(entry.targets().toString().contains(ID));
    }
    @Test void changedJobCannotOverwriteEvenIfOperationIdMatches() throws Exception {
        setup(); journal().put(entry); byte[] original=Files.readAllBytes(journalFile());
        var changed=new Entry(1,REALM,ID,JOB,entry.preparedAt(),entry.validUntil(),entry.checkpointDigest(),
                entry.checkpointRevision(),"d".repeat(64),2,entry.afterInventoryDigest(),entry.retainedObjectsDigest(),entry.targets());
        assertThrows(IllegalStateException.class,()->journal().put(changed));
        assertArrayEquals(original,Files.readAllBytes(journalFile()));
    }
    @Test void normalRunRequiresIndependentArmingAndPreservesNonTargets() throws Exception {
        setup(); var server=new Server(); var coordinator=coordinator(server,NOW); var before=hashes();
        coordinator.prepare(entry); assertEquals(0,deleteCalls); coordinator.resume(JOB);
        assertEquals(7,server.readFile().phase()); assertEquals(3,deleteCalls);
        entry.targets().forEach(t->before.remove(t.key())); assertEquals(before,hashes());
        coordinator(new Server(),NOW).resume(JOB); assertEquals(3,deleteCalls);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7})
    void acknowledgementLostAtEveryStageResumesFromReopenedState(int phase) throws Exception {
        setup(); var server=new Server(); var first=coordinator(server,NOW); first.prepare(entry);
        server.failAt=phase; server.failAfter=true; blocked(()->first.resume(JOB));
        assertEquals(phase,server.readFile().phase());
        objects=new FileErasureObjectStore(objectRoot,REALM,ID,safety);
        coordinator(new Server(),NOW.plusSeconds(1)).resume(JOB);
        assertEquals(7,new Server().readFile().phase()); assertEquals(3,deleteCalls);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7})
    void failedCasAtEveryStageDoesNotAdvanceLocallyAndRetriesExactJob(int phase) throws Exception {
        setup(); var server=new Server(); var first=coordinator(server,NOW); first.prepare(entry);
        server.failAt=phase; blocked(()->first.resume(JOB)); assertEquals(phase-1,server.readFile().phase());
        coordinator(new Server(),NOW.plusSeconds(1)).resume(JOB);
        assertEquals(7,new Server().readFile().phase());
        assertEquals(phase%2==0?4:3,deleteCalls); // Lost post-delete CAS safely repeats the absent step.
    }
    @Test void lostPrepareAcknowledgementNeverDeletesAndResumeUsesSavedJob() throws Exception {
        setup(); var server=new Server(); server.failPrepare=true; var first=coordinator(server,NOW);
        blocked(()->first.prepare(entry)); assertEquals(0,deleteCalls);
        coordinator(new Server(),NOW).resume(JOB); assertEquals(3,deleteCalls);
    }
    @Test void missingIndependentPrepareDoesNotUseLocalJournalAsAuthorization() throws Exception {
        setup(); journal().put(entry); blocked(()->coordinator(new Server(),NOW).resume(JOB)); assertEquals(0,deleteCalls);
    }
    @Test void missingOrCorruptedJournalStopsWithoutRecreation() throws Exception {
        setup(); var first=coordinator(new Server(),NOW); first.prepare(entry);
        byte[] bytes=Files.readAllBytes(journalFile()); Files.delete(journalFile());
        blocked(()->first.resume(JOB)); assertFalse(Files.exists(journalFile()));
        bytes[bytes.length-1]^=1; Files.write(journalFile(),bytes);
        blocked(()->first.resume(JOB)); assertEquals(0,deleteCalls);
    }
    @Test void staleEvidenceAndClockBeforePreparationStopNewDeletion() throws Exception {
        setup(); coordinator(new Server(),NOW).prepare(entry);
        blocked(()->coordinator(new Server(),NOW.plusSeconds(601)).resume(JOB));
        blocked(()->coordinator(new Server(),NOW.minusSeconds(1)).resume(JOB)); assertEquals(0,deleteCalls);
    }
    @Test void changedIndependentBindingAndFreshContextFailureStop() throws Exception {
        setup(); var server=new Server(); var first=coordinator(server,NOW); first.prepare(entry);
        server.save(new State("foreign","f".repeat(64),0)); blocked(()->first.resume(JOB));
        server.save(new State("revision-0",entry.digest(),0)); contextFailure=true;
        blocked(()->first.resume(JOB)); assertEquals(0,deleteCalls);
    }
    @Test void missingTargetBeforeArmingIsDataLossNotSuccessfulDeletion() throws Exception {
        setup(); var first=coordinator(new Server(),NOW); first.prepare(entry);
        Files.delete(objectFile(entry.targets().getFirst().key())); blocked(()->first.resume(JOB)); assertEquals(0,deleteCalls);
    }
    @Test void differentCiphertextAtArmedStepAndMissingSurvivorAreRejected() throws Exception {
        setup(); var server=new Server(); var first=coordinator(server,NOW); first.prepare(entry);
        server.failAt=2; blocked(()->first.resume(JOB)); // Armed phase 1, first file already absent.
        byte[] unrelated=objects.read(entry.targets().get(1).key());
        Files.write(objectFile(entry.targets().getFirst().key()),unrelated);
        blocked(()->coordinator(new Server(),NOW).resume(JOB)); assertEquals(1,deleteCalls);
        Files.delete(objectFile(entry.targets().getFirst().key()));
        var survivor=hashes().keySet().stream().filter(k->entry.targets().stream().noneMatch(t->t.key().equals(k))).findFirst().orElseThrow();
        Files.delete(objectFile(survivor)); blocked(()->coordinator(new Server(),NOW).resume(JOB)); assertEquals(1,deleteCalls);
    }
    @Test void alreadyCompletedStageReappearingStopsEvenWithExactOriginalBytes() throws Exception {
        setup(); var server=new Server(); var first=coordinator(server,NOW); first.prepare(entry);
        byte[] original=objects.read(entry.targets().getFirst().key());server.failAt=3;
        blocked(()->first.resume(JOB)); assertEquals(2,server.readFile().phase());
        Files.write(objectFile(entry.targets().getFirst().key()),original);
        blocked(()->coordinator(new Server(),NOW).resume(JOB)); assertEquals(1,deleteCalls);
    }
    @Test void leaseFailurePreventsJournalAndIndependentWrites() throws Exception {
        setup(); var first=coordinator(new Server(),NOW); leaseFailure=true;
        blocked(()->first.prepare(entry)); assertFalse(Files.exists(journalFile()));assertFalse(Files.exists(statePath));
    }
    @Test void journalFsyncFailureNeverPreparesIndependentStateAndExactRetryReforces() throws Exception {
        setup(); var first=coordinator(new Server(),NOW); safety.failSync=true;
        blocked(()->first.prepare(entry)); assertFalse(Files.exists(statePath));assertEquals(0,deleteCalls);
        safety.failSync=false; coordinator(new Server(),NOW).prepare(entry);
        coordinator(new Server(),NOW).resume(JOB);assertEquals(3,deleteCalls);
    }
    @Test void restartCanDiscoverOnlyValidatedBoundedJobs() throws Exception {
        setup(); assertEquals(List.of(),journal().operations(0)); journal().put(entry);
        assertEquals(List.of(JOB),journal().operations(1));
        assertThrows(IllegalStateException.class,()->journal().operations(0));
        Files.write(journalRoot.resolve("unexpected"),new byte[]{1});
        assertThrows(IllegalStateException.class,()->journal().operations(10));
    }
    @Test void partialRecoveryRecordIsNotOverwrittenOrHiddenFromDiscovery() throws Exception {
        setup(); Files.write(journalFile(),new byte[]{1,2,3});
        assertThrows(IllegalStateException.class,()->journal().put(entry));
        assertThrows(IllegalStateException.class,()->journal().operations(10));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(journalFile()));
    }
    @Test void copiedCiphertextCannotMasqueradeAsAnotherOperation() throws Exception {
        setup(); journal().put(entry);String different=new UUID(0,100).toString();
        Files.copy(journalFile(),journalRoot.resolve("retirement-"+different+".bin"));
        assertThrows(IllegalStateException.class,()->journal().read(different));
        assertThrows(IllegalStateException.class,()->journal().operations(10));
    }
    @Test void invalidTargetOrderOrEpochCannotBeSerializedIntoARecoveryJob() throws Exception {
        setup();var swapped=new ArrayList<>(entry.targets());Collections.swap(swapped,0,1);
        assertThrows(IllegalStateException.class,()->new Entry(1,REALM,ID,JOB,entry.preparedAt(),entry.validUntil(),
                entry.checkpointDigest(),entry.checkpointRevision(),entry.planDigest(),2,entry.afterInventoryDigest(),
                entry.retainedObjectsDigest(),swapped));
        assertThrows(IllegalStateException.class,()->new Entry(1,REALM,new UUID(0,3).toString(),JOB,entry.preparedAt(),entry.validUntil(),
                entry.checkpointDigest(),entry.checkpointRevision(),entry.planDigest(),2,entry.afterInventoryDigest(),
                entry.retainedObjectsDigest(),entry.targets()));
    }
}
