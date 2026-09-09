package com.example.toiletbatch.account;

import com.geupddong.account.CheckpointedErasureLedger.Head;
import com.geupddong.account.ErasureCheckpoint;
import com.geupddong.account.ErasureCompletion;
import com.geupddong.account.ErasureRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import static com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;

/**
 * Pure dry-run: binds retention evidence to an exact identity and independent checkpoint.
 * No credentials, network, scheduler or deletion capability. A plan is NOT authorization.
 * The caller must obtain a complete, consistent snapshot under the operational exclusion guard.
 */
public final class ErasureRetirementPlanner {
    public static final Duration MAX_SNAPSHOT_AGE = Duration.ofMinutes(10);
    public enum Outcome { HOLD_NO_COMPLETION, HOLD_REVIEW, REVIEW_CANDIDATE }
    public record Proof(ErasureCompletion completion, Evidence evidence) { }
    /** Per-item digests remain linkable operational data; do not publish the item list. */
    public record Item(String targetDigest, Outcome outcome, Status reviewStatus,
                       Set<Requirement> missing, Instant earliestReviewAt) {
        public Item { missing = Set.copyOf(missing); }
    }
    public record Plan(int total, int candidates, String checkpointDigest,
                       String planDigest, Instant validUntil, List<Item> items) {
        public Plan { items = List.copyOf(items); }
        @Override public String toString() {
            return "ErasureRetirementPlan[total=" + total + ", candidates=" + candidates
                    + ", held=" + (total - candidates) + ", executionAllowed=false]";
        }
    }

    public Plan plan(String expectedRealm, String expectedEpoch, Instant now, Instant capturedAt,
                     Head head, List<ErasureRecord> intents, List<ErasureRecord> catalogue,
                     Map<String, Proof> proofs) {
        try {
            Objects.requireNonNull(head.revision());
            if (head.revision().isBlank() || now == null || capturedAt == null
                    || capturedAt.isAfter(now) || now.isAfter(capturedAt.plus(MAX_SNAPSHOT_AGE))) fail();
            var cp = head.checkpoint();
            if (!cp.realm().equals(expectedRealm) || !cp.databaseEpoch().equals(expectedEpoch)
                    || Instant.parse(cp.recordedAt()).isAfter(capturedAt)) fail();
            var first = inventory(cp, intents);
            if (!first.equals(inventory(cp, catalogue))) fail();
            if (!first.keySet().containsAll(proofs.keySet())) fail();

            var sorted = new TreeMap<String, ErasureRecord>();
            for (var record : intents) sorted.put(record.objectKey(), record);
            var items = new ArrayList<Item>();
            var canonical = new StringBuilder("geupddong-retirement-plan-v1\n")
                    .append(MINIMUM_REVIEW_DELAY).append('|').append(MAX_EVIDENCE_AGE).append('|')
                    .append(MAX_SNAPSHOT_AGE).append('\n')
                    .append(cp.digest()).append('\n').append(head.revision()).append('\n')
                    .append(capturedAt).append('\n').append(now).append('\n');
            int candidates = 0;
            Instant validUntil = capturedAt.plus(MAX_SNAPSHOT_AGE);
            for (var entry : sorted.entrySet()) {
                var record = entry.getValue();
                String target = ErasureCompletion.digest(record);
                var proof = proofs.get(entry.getKey());
                if (proof == null || proof.completion() == null) {
                    items.add(new Item(target, Outcome.HOLD_NO_COMPLETION, null,
                            EnumSet.allOf(Requirement.class), null));
                    canonical.append(target).append("|NO_COMPLETION\n");
                    continue;
                }
                var receipt = proof.completion();
                Instant absent = Instant.parse(receipt.firstConfirmedAbsentAt());
                if (!receipt.realm().equals(expectedRealm) || !receipt.databaseEpoch().equals(expectedEpoch)
                        || !receipt.withdrawalKey().equals(record.withdrawalKey())
                        || !receipt.intentDigest().equals(target) || absent.isAfter(capturedAt)
                        || absent.isBefore(LocalDateTime.parse(record.eligibleAt())
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant())) fail();
                var evidence = proof.evidence();
                // An unbound boolean checklist must never certify another identity's absence window.
                if (evidence != null && (!absent.equals(evidence.firstConfirmedAbsentAt())
                        || evidence.checkedAt() == null || evidence.checkedAt().isAfter(capturedAt))) fail();
                var decision = new ErasureRetentionReviewPolicy().evaluate(now, evidence);
                boolean candidate = decision.status() == Status.REVIEW_CANDIDATE;
                if (candidate) {
                    candidates++;
                    Instant evidenceExpiry = evidence.checkedAt().plus(MAX_EVIDENCE_AGE);
                    if (evidenceExpiry.isBefore(validUntil)) validUntil = evidenceExpiry;
                }
                items.add(new Item(target, candidate ? Outcome.REVIEW_CANDIDATE : Outcome.HOLD_REVIEW,
                        decision.status(), decision.missing(), decision.earliestReviewAt()));
                canonical.append(target).append('|').append(receipt.firstConfirmedAbsentAt()).append('|')
                        .append(evidence == null ? "NO_EVIDENCE" : evidence.checkedAt()).append('|')
                        .append(decision.status()).append('|');
                for (var required : Requirement.values())
                    canonical.append(decision.missing().contains(required) ? '0' : '1');
                canonical.append('\n');
            }
            return new Plan(items.size(), candidates, cp.digest(),
                    ErasureCheckpoint.hash(canonical.toString().getBytes(StandardCharsets.UTF_8)),
                    validUntil, items);
        } catch (RuntimeException invalid) {
            // Never expose record contents or provider exception messages to an operator log.
            throw new IllegalStateException("ERASURE_RETIREMENT_SNAPSHOT_INVALID");
        }
    }

    private Map<String, String> inventory(ErasureCheckpoint cp, List<ErasureRecord> records) {
        if (records == null || records.size() != cp.count()) fail();
        var values = new TreeMap<String, String>();
        var users = new HashSet<Long>();
        for (var record : records) {
            if (!cp.realm().equals(record.realm()) || !users.add(record.userId())
                    || values.put(record.objectKey(), ErasureCompletion.digest(record)) != null) fail();
        }
        if (!cp.inventorySha256().equals(cp.inventoryDigest(values))) fail();
        return values;
    }
    private static void fail() { throw new IllegalStateException("INVALID"); }
}
