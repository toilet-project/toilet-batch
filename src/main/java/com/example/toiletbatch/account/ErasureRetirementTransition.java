package com.example.toiletbatch.account;

import com.geupddong.account.CheckpointedErasureLedger.Head;
import com.geupddong.account.ErasureCompletion;
import com.geupddong.account.ErasureRecord;
import java.time.Instant;
import java.util.*;

/**
 * Pure transition verification, not an executor or durable authorization.
 * Does not relax the v1 checkpoint store's prohibition on decreasing counts.
 * Storage adapters must separately provide a guarded snapshot and durable prepare/CAS protocol.
 */
public final class ErasureRetirementTransition {
    public static final class Prepared {
        private final Head head;
        private final Instant preparedAt, validUntil;
        private final Map<String, ErasureRecord> original;
        private final Map<String, ErasureCompletion> completions;
        private final Set<String> selected;

        private Prepared(Head head, Instant preparedAt, Instant validUntil,
                         Map<String, ErasureRecord> original, Map<String, ErasureCompletion> completions,
                         Set<String> selected) {
            this.head = head; this.preparedAt = preparedAt; this.validUntil = validUntil;
            this.original = Map.copyOf(original); this.completions = Map.copyOf(completions);
            this.selected = Set.copyOf(selected);
        }
        @Override public String toString() {
            return "ErasureRetirementPrepared[targets=" + selected.size() + ", executionAllowed=false]";
        }
    }

    public static final class Observation {
        private final Prepared owner;
        private final Map<String, Integer> stages;
        private final boolean complete;
        private final Instant observedAt;
        private Observation(Prepared owner, Map<String, Integer> stages, boolean complete, Instant at) {
            this.owner = owner; this.stages = Map.copyOf(stages); this.complete = complete; this.observedAt = at;
        }
        public boolean removalComplete() { return complete; }
        public int remainingCount() {
            if (!complete) fail();
            return owner.original.size() - owner.selected.size();
        }
        public String remainingInventoryDigest() {
            if (!complete) fail();
            var inventory = new TreeMap<String, String>();
            owner.original.forEach((key, value) -> {
                if (!owner.selected.contains(key)) inventory.put(key, ErasureCompletion.digest(value));
            });
            return owner.head.checkpoint().inventoryDigest(inventory);
        }
        @Override public String toString() {
            return "ErasureRetirementObservation[removalComplete=" + complete + ", executionAllowed=false]";
        }
    }

    /** Completion maps throughout this verifier are keyed by the original intent objectKey. */
    public Prepared prepare(String realm, String epoch, Instant now, Instant capturedAt, Head head,
                            List<ErasureRecord> intents, List<ErasureRecord> catalogue,
                            Map<String, ErasureRetirementPlanner.Proof> proofs, Set<String> selectedKeys) {
        try {
            // Recompute evidence; never trust an externally constructed Plan record.
            var plan = new ErasureRetirementPlanner().plan(realm, epoch, now, capturedAt, head, intents, catalogue, proofs);
            var original = records(intents);
            if (selectedKeys == null || selectedKeys.isEmpty() || !original.keySet().containsAll(selectedKeys)) fail();
            var eligible = new HashSet<String>();
            plan.items().stream().filter(item -> item.outcome() == ErasureRetirementPlanner.Outcome.REVIEW_CANDIDATE)
                    .forEach(item -> eligible.add(item.targetDigest()));
            for (String key : selectedKeys)
                if (!eligible.contains(ErasureCompletion.digest(original.get(key)))) fail();
            var completions = new HashMap<String, ErasureCompletion>();
            proofs.forEach((key, proof) -> { if (proof != null && proof.completion() != null) completions.put(key, proof.completion()); });
            return new Prepared(head, now, plan.validUntil(), original, completions, selectedKeys);
        } catch (RuntimeException invalid) { throw failure(); }
    }

    /**
     * Verify ordered removal: intent -> catalogue -> completion. Retries may keep the same stage.
     * Previous observations must belong to this exact plan; removed objects must not reappear.
     * An expired plan requires new guarded evidence (not implemented by this pure verifier).
     */
    public Observation observe(Prepared prepared, Observation previous, Instant now, Head unchangedHead,
                               List<ErasureRecord> intents, List<ErasureRecord> catalogue,
                               Map<String, ErasureCompletion> completions) {
        try {
            if (prepared == null || now == null || now.isBefore(prepared.preparedAt)
                    || now.isAfter(prepared.validUntil) || !prepared.head.equals(unchangedHead)
                    || (previous != null && (previous.owner != prepared || now.isBefore(previous.observedAt)))) fail();
            var i = records(intents); var c = records(catalogue);
            validateSubset(prepared.original, i, prepared.selected);
            validateSubset(prepared.original, c, prepared.selected);
            validateSubset(prepared.completions, completions, prepared.selected);
            var stages = new HashMap<String, Integer>();
            boolean complete = true;
            for (String key : prepared.selected) {
                boolean intent = i.containsKey(key), catalog = c.containsKey(key), receipt = completions.containsKey(key);
                int stage;
                if (intent && catalog && receipt) stage = 0;
                else if (!intent && catalog && receipt) stage = 1;
                else if (!intent && !catalog && receipt) stage = 2;
                else if (!intent && !catalog && !receipt) stage = 3;
                else { fail(); return null; }
                if (previous != null && stage < previous.stages.get(key)) fail();
                stages.put(key, stage);
                complete &= stage == 3;
            }
            return new Observation(prepared, stages, complete, now);
        } catch (RuntimeException invalid) { throw failure(); }
    }

    private static Map<String, ErasureRecord> records(List<ErasureRecord> records) {
        var result = new HashMap<String, ErasureRecord>();
        for (var record : records) if (result.put(record.objectKey(), record) != null) fail();
        return result;
    }
    private static <T> void validateSubset(Map<String, T> original, Map<String, T> observed, Set<String> selected) {
        if (observed == null || !original.keySet().containsAll(observed.keySet())) fail();
        observed.forEach((key, value) -> { if (!Objects.equals(original.get(key), value)) fail(); });
        for (String key : original.keySet()) if (!selected.contains(key) && !observed.containsKey(key)) fail();
    }
    private static void fail() { throw failure(); }
    private static IllegalStateException failure() { return new IllegalStateException("ERASURE_RETIREMENT_TRANSITION_INVALID"); }
}
