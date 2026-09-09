package com.geupddong.account;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/** Read-only restore gate. A caller-supplied count or a self-consistent local hash is NOT sufficient. */
public final class VerifiedErasureSnapshot {
    private VerifiedErasureSnapshot() { }

    public static List<ErasureRecord> read(ObjectErasureLedger ledger, CheckpointedErasureLedger.Store store,
                                           String realm, String epoch, Clock clock) {
        try {
            var before = store.read();
            var cp = before.checkpoint();
            if (!cp.realm().equals(realm) || !cp.databaseEpoch().equals(epoch)
                    || Instant.parse(cp.recordedAt()).isAfter(clock.instant())) throw unavailable();
            var intents = ledger.readAll(cp.count());
            var catalogue = ledger.readCatalogue(cp.count());
            var values = inventory(intents, realm);
            if (!values.equals(inventory(catalogue, realm)) || !cp.inventorySha256().equals(cp.inventoryDigest(values)))
                throw unavailable();
            if (!before.equals(store.read())) throw unavailable(); // Concurrent advancement: retry only after freezing writers.
            return List.copyOf(intents);
        } catch (Exception ignored) { throw unavailable(); }
    }

    private static TreeMap<String,String> inventory(List<ErasureRecord> records, String realm) {
        var result = new TreeMap<String,String>();
        var users = new HashSet<Long>();
        for (var record : records) {
            if (!realm.equals(record.realm()) || !users.add(record.userId())
                    || result.put(record.objectKey(), ErasureCompletion.digest(record)) != null) throw unavailable();
        }
        return result;
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("ERASURE_RESTORE_INVENTORY_UNVERIFIED"); }
}
