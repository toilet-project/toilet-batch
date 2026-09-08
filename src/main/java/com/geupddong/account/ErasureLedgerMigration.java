package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

/** Offline operator primitive, never scheduled or wired into the application.
 * Copies authenticated ciphertext without re-encryption, deletion or checkpoint writes.
 * Writers must be frozen externally; every phase rechecks the independent head.
 */
public final class ErasureLedgerMigration {
    private static final int LIMIT = 10000;
    private final ErasureObjectStore source, target;
    private final ErasureCipher cipher;
    private final CheckpointedErasureLedger.Store checkpoints;
    private final String realm, epoch;
    private final Clock clock;

    public record Summary(int intents, int catalogues, int completions, int pendingObjects,
                          String ciphertextInventoryHash, boolean applied) { }
    private record Snapshot(CheckpointedErasureLedger.Head head, SortedMap<String, byte[]> objects,
                            int count, int completions, String digest) { }

    public ErasureLedgerMigration(ErasureObjectStore source, ErasureObjectStore target, ErasureCipher cipher,
            CheckpointedErasureLedger.Store checkpoints, String realm, String epoch, Clock clock) {
        if (source == target || !realm.matches("[a-z0-9-]{3,40}")
                || !UUID.fromString(epoch).toString().equals(epoch)) throw unavailable();
        this.source=source; this.target=target; this.cipher=cipher; this.checkpoints=checkpoints;
        this.realm=realm; this.epoch=epoch; this.clock=clock;
    }

    /** Default analysis path. No writer is invoked. The digest binds a subsequent approved execution. */
    public Summary dryRun() { return run(false, null, false); }

    /** Caller must separately authorize exact destination, freeze writers and retain the source. */
    public Summary copyApproved(String expectedCiphertextInventoryHash, boolean writersFrozen) {
        return run(true, expectedCiphertextInventoryHash, writersFrozen);
    }

    private Summary run(boolean apply, String expected, boolean frozen) {
        try {
            if (apply && (!frozen || expected == null || !expected.matches("[a-f0-9]{64}"))) throw unavailable();
            Snapshot before = capture();
            if (apply && !before.digest.equals(expected)) throw unavailable();
            var existing = objects(target);
            verifySubset(existing, before.objects);
            int pending = before.objects.size() - existing.size();
            requireHead(before.head);
            if (apply) for (var item : before.objects.entrySet()) {
                requireHead(before.head);
                // Always acknowledge durability on retry, but never overwrite different bytes.
                byte[] prior = target.read(item.getKey());
                if (prior != null && !Arrays.equals(prior, item.getValue())) throw unavailable();
                target.putIfAbsent(item.getKey(), item.getValue());
                if (!Arrays.equals(item.getValue(), target.read(item.getKey()))) throw unavailable();
            }
            Snapshot after = capture();
            if (!before.head.equals(after.head) || !before.digest.equals(after.digest)) throw unavailable();
            var destination = objects(target);
            verifySubset(destination, before.objects);
            if (apply && destination.size() != before.objects.size()) throw unavailable();
            requireHead(before.head);
            return new Summary(before.count, before.count, before.completions, apply ? 0 : pending, before.digest, apply);
        } catch (Exception ignored) { throw unavailable(); }
    }

    private Snapshot capture() throws Exception {
        var head = checkpoints.read(); var cp = head.checkpoint();
        if (cp.count() > LIMIT / 2) throw unavailable();
        var ledger = new ObjectErasureLedger(source, cipher, realm, true);
        var records = VerifiedErasureSnapshot.read(ledger, checkpoints, realm, epoch, clock);
        requireHead(head);
        var values = objects(source);
        var expected = new HashSet<String>(); var byKey = new HashMap<String, ErasureRecord>();
        for (var record : records) {
            expected.add(record.objectKey());
            expected.add("catalogue-v1/" + realm + "/" + record.withdrawalKey() + ".bin");
            byKey.put(record.withdrawalKey(), record);
            // Authenticate the exact captured bytes too, not only the earlier ledger read.
            if (!record.equals(cipher.decrypt(realm, record.objectKey(), values.get(record.objectKey())))) throw unavailable();
            String key = "catalogue-v1/" + realm + "/" + record.withdrawalKey() + ".bin";
            if (!record.equals(new ObjectMapper().readValue(cipher.decryptDocument(realm, key, values.get(key)), ErasureRecord.class))) throw unavailable();
        }
        int completions=0;
        for (var entry : values.entrySet()) {
            if (!entry.getKey().startsWith("completion-v1/" + realm + "/")) continue;
            var receipt = new ObjectMapper().readValue(cipher.decryptDocument(realm, entry.getKey(), entry.getValue()), ErasureCompletion.class);
            var intent = byKey.get(receipt.withdrawalKey());
            if (intent == null || !receipt.objectKey().equals(entry.getKey()) || !receipt.realm().equals(realm)
                    || !receipt.intentDigest().equals(ErasureCompletion.digest(intent))) throw unavailable();
            Instant at=Instant.parse(receipt.firstConfirmedAbsentAt());
            if (at.isAfter(clock.instant()) || at.isBefore(LocalDateTime.parse(intent.eligibleAt()).atZone(ZoneId.of("Asia/Seoul")).toInstant())) throw unavailable();
            // Preserve all database epochs; do not silently drop historical completion receipts.
            expected.add(entry.getKey()); completions++;
        }
        if (!expected.equals(values.keySet()) || records.size()!=cp.count()) throw unavailable();
        var hashes = new TreeMap<String,String>(); values.forEach((k,v)->hashes.put(k, ErasureCheckpoint.hash(v)));
        String digest=ErasureCheckpoint.hash(new ObjectMapper().writeValueAsBytes(List.of("local-ledger-copy-v1",head.revision(),cp.digest(),hashes)));
        requireHead(head);
        return new Snapshot(head, values, records.size(), completions, digest);
    }

    private SortedMap<String,byte[]> objects(ErasureObjectStore store) {
        var values = new TreeMap<String,byte[]>();
        for (String kind : List.of("v1", "catalogue-v1", "completion-v1")) {
            String prefix=kind+"/"+realm+"/";
            for (String key : store.list(prefix, LIMIT)) {
                if (!key.startsWith(prefix) || values.size() >= LIMIT || values.containsKey(key)) throw unavailable();
                byte[] bytes=store.read(key);
                if (bytes==null || bytes.length<34 || bytes.length>8192) throw unavailable();
                values.put(key, bytes.clone());
            }
        }
        return values;
    }
    private void verifySubset(Map<String,byte[]> destination, Map<String,byte[]> original) {
        for (var item : destination.entrySet())
            if (!Arrays.equals(original.get(item.getKey()), item.getValue())) throw unavailable();
    }
    private void requireHead(CheckpointedErasureLedger.Head expected) {
        if (!expected.equals(checkpoints.read())) throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("ERASURE_LOCAL_MIGRATION_UNVERIFIED"); }
}
