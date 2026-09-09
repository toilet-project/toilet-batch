package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.geupddong.account.RetirementRecoveryJournal.Entry;

/** Guarded batch/CLI orchestration; does not relax the original v1 checkpoint contract.
 * All reader compatibility must be verified before enabling runtime writes.
 */
final class RetirementRecoveryCoordinator {
    /** Even phases 0/2/4: ready; odd 1/3/5: independently armed deletion; 6: removed; 7: committed. */
    record State(String revision, String journalDigest, int phase) {
        State {
            if(revision==null || revision.isBlank() || !RetirementRecoveryJournal.hash(journalDigest)
                    || phase<0 || phase>8) throw failure();
        }
        @Override public String toString() {return "RetirementState[phase="+phase+"]";}
    }
    interface Independent {
        /** Read verified authoritative state; network failure is never represented as null.
         * Null means no active retirement after verifying the existing independent checkpoint. */
        State read();
        /** Global CAS from no active retirement, bound to entry's original checkpoint/epoch.
         * All writers and restore consumers must fail closed while preparation exists. */
        void prepare(Entry entry);
        /** Durable CAS exactly phase+1. Phase 7 must atomically commit the validated after inventory
         * and its count (beforeCount-1), not just toggle a local flag. Lost ACK must throw. */
        void advance(State expected, Entry entry, int nextPhase);
        default String authorizedUntil(State expected,Entry entry){return entry.validUntil();}
        default void renew(State expected,Entry entry,String evidenceDigest){throw failure();}
    }
    interface Objects {
        /** Complete bounded, locked inventory of ciphertext hashes, not only selected objects. */
        Map<String,String> inventory();
        void removeExact(String key,String ciphertextSha256,boolean preparedRetry);
    }
    interface Context {
        /** Under the global lease: recompute eligibility and before/after inventory, verify DB epoch,
         * absence/copy evidence and authoritative checkpoint. A journal alone never satisfies this.
         * For resumed phases, validate the immutable original prepare plus fresh evidence. */
        void verify(Entry entry, State state);
        default String reviewDigest(Entry entry,State state){throw failure();}
    }
    interface Lease { void run(Runnable work); }
    private final RetirementRecoveryJournal journal;
    private final Independent independent;
    private final Objects objects;
    private final Context context;
    private final Lease lease;
    private final Clock clock;
    RetirementRecoveryCoordinator(RetirementRecoveryJournal journal, Independent independent,
                                  Objects objects, Context context, Lease lease, Clock clock) {
        this.journal=java.util.Objects.requireNonNull(journal);
        this.independent=java.util.Objects.requireNonNull(independent);
        this.objects=java.util.Objects.requireNonNull(objects);
        this.context=java.util.Objects.requireNonNull(context);
        this.lease=java.util.Objects.requireNonNull(lease);
        this.clock=java.util.Objects.requireNonNull(clock);
    }
    void prepare(Entry entry) {
        prepare(() -> entry);
    }
    void prepare(java.util.function.Supplier<Entry> factory) {
        guarded(() -> {
            var existing=independent.read();
            if(existing!=null && existing.phase()!=8) throw failure();
            Entry entry=factory.get();
            var pending=journal.operations(2);
            if(!pending.isEmpty() && !(existing==null && pending.equals(List.of(entry.operationId()))))throw failure();
            fresh(entry); context.verify(entry,null); inventory(entry,0);
            journal.put(entry); // Must survive restart before asking independent preparation.
            fresh(entry);
            independent.prepare(entry);
            State accepted=state(entry);
            if(accepted.phase()!=0) throw failure();
            // No deletion in prepare, including uncertain acknowledgement.
        });
    }
    void resume(String operationId) {
        guarded(() -> {
            Entry entry=journal.read(operationId);
            for(int iteration=0;iteration<8;iteration++) {
                State current=state(entry); // Never resume based solely on a local progress counter.
                if(current.phase()<7) fresh(entry,current);
                context.verify(entry,current);
                inventory(entry,current.phase());
                if(current.phase()>=7) return;
                if(current.phase()%2==1) {
                    var target=entry.targets().get(current.phase()/2);
                    // Only independently ARMED steps may tolerate an already-absent exact retry.
                    fresh(entry,current);
                    objects.removeExact(target.key(),target.ciphertextSha256(),true);
                    inventory(entry,current.phase()+1);
                }
                fresh(entry,current);
                independent.advance(current,entry,current.phase()+1);
                State observed=state(entry);
                if(observed.phase()!=current.phase()+1 || observed.revision().equals(current.revision())) throw failure();
            }
            throw failure();
        });
    }
    void review(String operationId) {
        guarded(()->{
            Entry entry=journal.read(operationId);State before=state(entry);if(before.phase()>6)throw failure();
            context.verify(entry,before);inventory(entry,before.phase());String digest=context.reviewDigest(entry,before);
            if(!RetirementRecoveryJournal.hash(digest))throw failure();
            independent.renew(before,entry,digest);State after=state(entry);
            if(after.phase()!=before.phase() || after.revision().equals(before.revision()))throw failure();fresh(entry,after);
        });
    }
    private State state(Entry entry) {
        State state=independent.read();
        if(state==null || !state.journalDigest().equals(entry.digest())) throw failure();
        return state;
    }
    private void fresh(Entry entry) {
        Instant now=clock.instant();
        if(now.isBefore(Instant.parse(entry.preparedAt())) || now.isAfter(Instant.parse(entry.validUntil()))) throw failure();
    }
    private void fresh(Entry entry,State state) {
        Instant now=clock.instant();
        if(now.isBefore(Instant.parse(entry.preparedAt())) || now.isAfter(Instant.parse(independent.authorizedUntil(state,entry))))throw failure();
    }
    private void inventory(Entry entry,int phase) {
        var all=new TreeMap<>(objects.inventory());
        int done=phase/2;
        for(int i=0;i<3;i++) {
            var target=entry.targets().get(i); String actual=all.remove(target.key());
            boolean armed=phase<6 && phase%2==1 && i==done;
            if(actual!=null && !actual.equals(target.ciphertextSha256())) throw failure();
            if(i<done && actual!=null) throw failure();
            if(i>=done && !armed && actual==null) throw failure();
        }
        if(!inventoryDigest(all).equals(entry.retainedObjectsDigest())) throw failure();
    }
    static String inventoryDigest(Map<String,String> objects) {
        try {
            if(objects.size()>300000) throw failure();
            for(var item:objects.entrySet())
                if(item.getKey()==null || !RetirementRecoveryJournal.hash(item.getValue())) throw failure();
            return ErasureCheckpoint.hash(new ObjectMapper().writeValueAsBytes(new TreeMap<>(objects)));
        } catch(Exception ignored) {throw failure();}
    }
    private void guarded(Runnable work) {
        try {lease.run(work);} catch(RuntimeException ignored) {throw failure();}
    }
    private static IllegalStateException failure() {return new IllegalStateException("RETIREMENT_RECOVERY_BLOCKED");}
}
