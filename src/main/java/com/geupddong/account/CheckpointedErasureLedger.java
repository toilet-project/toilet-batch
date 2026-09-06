package com.geupddong.account;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** No SQL/Redis deletion is permitted until both R2 and independent Git acknowledgement succeed. */
public final class CheckpointedErasureLedger implements ErasureLedger {
    public record Head(String revision, ErasureCheckpoint checkpoint) { }
    public interface Store {
        Head read();
        void append(Head expected, ErasureCheckpoint next);
    }
    public interface SnapshotLedger extends ErasureLedger {
        List<ErasureRecord> catalogueAtMost(int maximum);
        List<ErasureRecord> intentsAtMost(int maximum);
    }
    @FunctionalInterface public interface Exclusive { void run(Runnable work); }
    private final SnapshotLedger ledger;
    private final Store store;
    private final Exclusive exclusive;
    private final Clock clock;
    private final String realm, epoch;
    public CheckpointedErasureLedger(SnapshotLedger ledger, Store store, Exclusive exclusive,
                                    Clock clock, String realm, String epoch) {
        this.ledger=ledger; this.store=store; this.exclusive=exclusive; this.clock=clock;
        this.realm=realm; this.epoch=epoch;
    }
    @Override public void ensureRecorded(ErasureRecord record) {
        try { exclusive.run(() -> advance(record)); }
        catch (RuntimeException ignored) { throw new IllegalStateException("ERASURE_CHECKPOINT_UNAVAILABLE"); }
    }
    private Map<String,String> records(List<ErasureRecord> values) {
        var result = new TreeMap<String,String>();
        var users = new HashSet<Long>();
        for (var value : values) {
            if (!realm.equals(value.realm()) || !users.add(value.userId())
                    || result.put(value.objectKey(), ErasureCompletion.digest(value)) != null) fail();
        }
        return result;
    }
    private boolean matches(ErasureCheckpoint cp, Map<String,String> values) {
        return cp.count()==values.size() && cp.inventorySha256().equals(cp.inventoryDigest(values));
    }
    private Map<String,String> reconcile(ErasureCheckpoint cp, List<ErasureRecord> actual, ErasureRecord request) {
        for (var value : actual)
            if (value.userId()==request.userId() && !value.objectKey().equals(request.objectKey())) fail();
        var map = records(actual);
        String key=request.objectKey(), digest=ErasureCompletion.digest(request);
        if (map.containsKey(key) && !map.get(key).equals(digest)) fail();
        var without = new TreeMap<>(map); without.remove(key);
        var with = new TreeMap<>(map); with.put(key,digest);
        // The ONLY permitted mismatch is this exact pending intent. Never infer missing members from count.
        if (!matches(cp,map) && !matches(cp,without) && !matches(cp,with)) fail();
        return with;
    }
    private void advance(ErasureRecord record) {
        if (!realm.equals(record.realm())) fail();
        Head head=store.read();
        ErasureCheckpoint cp=head.checkpoint();
        Instant now=clock.instant();
        if (!realm.equals(cp.realm()) || !epoch.equals(cp.databaseEpoch())
                || Instant.parse(cp.recordedAt()).isAfter(now) || cp.count()>=100000) fail();
        var catalogue=reconcile(cp,ledger.catalogueAtMost(cp.count()+1),record);
        var intents=reconcile(cp,ledger.intentsAtMost(cp.count()+1),record);
        if (!catalogue.equals(intents)) fail();
        ledger.ensureRecorded(record);
        if (!catalogue.equals(records(ledger.catalogueAtMost(cp.count()+1)))
                || !catalogue.equals(records(ledger.intentsAtMost(cp.count()+1)))) fail();
        if (!matches(cp,catalogue)) {
            var next=new ErasureCheckpoint(1,realm,epoch,Math.addExact(cp.sequence(),1),catalogue.size(),
                    cp.inventoryDigest(catalogue),cp.digest(),now.toString());
            store.append(head,next); // Timeout/CAS failure blocks deletion; exact intent retry reconciles R2.
        }
    }
    private static void fail() { throw new IllegalStateException("ERASURE_CHECKPOINT_MISMATCH"); }
    @Override public void close() { ledger.close(); }
}
