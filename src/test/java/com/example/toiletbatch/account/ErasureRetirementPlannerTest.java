package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;
import static com.example.toiletbatch.account.ErasureRetirementPlanner.*;

class ErasureRetirementPlannerTest {
    static final String EPOCH = "00000000-0000-0000-0000-000000000001";
    static final Instant ABSENT = Instant.parse("2026-09-08T00:00:00Z");
    static final Instant NOW = ABSENT.plus(Duration.ofDays(32));
    final ErasureRetirementPlanner planner = new ErasureRetirementPlanner();
    ErasureRecord record(long id) {
        return new ErasureRecord(1, "test", id, "2026-09-01T09:00:00",
                new UUID(0, id).toString(), "2026-09-08T09:00:00");
    }
    CheckpointedErasureLedger.Head head(List<ErasureRecord> records) {
        var blank = new ErasureCheckpoint(1, "test", EPOCH, 1, 0, "0".repeat(64), "", ABSENT.toString());
        var inventory = new TreeMap<String, String>();
        for (var r : records) inventory.put(r.objectKey(), ErasureCompletion.digest(r));
        return new CheckpointedErasureLedger.Head("revision-1", new ErasureCheckpoint(1, "test", EPOCH,
                1, records.size(), blank.inventoryDigest(inventory), "", ABSENT.toString()));
    }
    Proof proof(ErasureRecord r, Set<Requirement> requirements) {
        return new Proof(ErasureCompletion.observed(r, EPOCH, ABSENT), new Evidence(ABSENT, NOW, requirements));
    }
    Plan plan(List<ErasureRecord> records, Map<String, Proof> proofs) {
        return planner.plan("test", EPOCH, NOW, NOW, head(records), records, records, proofs);
    }
    void invalid(Runnable action) {
        assertEquals("ERASURE_RETIREMENT_SNAPSHOT_INVALID",
                assertThrows(IllegalStateException.class, action::run).getMessage());
    }
    @Test void emptyVerifiedSnapshotIsNotInventedWork() {
        var p = plan(List.of(), Map.of());
        assertEquals(0, p.total()); assertEquals(0, p.candidates());
    }
    @Test void exactBoundaryProducesReviewOnlyAndNoIdentitiesInSummary() {
        var r = record(100);
        var p = plan(List.of(r), Map.of(r.objectKey(), proof(r, EnumSet.allOf(Requirement.class))));
        assertEquals(1, p.candidates()); assertEquals(Outcome.REVIEW_CANDIDATE, p.items().getFirst().outcome());
        assertTrue(p.toString().contains("executionAllowed=false"));
        assertFalse(p.toString().contains(r.withdrawalKey()));
        assertFalse(p.toString().contains(r.userCreatedAt()));
        assertThrows(UnsupportedOperationException.class, () -> p.items().clear());
    }
    @ParameterizedTest @EnumSource(Requirement.class)
    void eachMissingEvidenceHoldsTheRecord(Requirement missing) {
        var r = record(100); var all = EnumSet.allOf(Requirement.class); all.remove(missing);
        var p = plan(List.of(r), Map.of(r.objectKey(), proof(r, all)));
        assertEquals(0, p.candidates()); assertEquals(Set.of(missing), p.items().getFirst().missing());
    }
    @Test void missingCompletionIsCountedNotSilentlyDropped() {
        var p = plan(List.of(record(1)), Map.of());
        assertEquals(1, p.total()); assertEquals(0, p.candidates());
        assertEquals(Outcome.HOLD_NO_COMPLETION, p.items().getFirst().outcome());
    }
    @Test void missingEvidenceHoldsCompletedRecord() {
        var r = record(1);
        var p = plan(List.of(r), Map.of(r.objectKey(), new Proof(ErasureCompletion.observed(r, EPOCH, ABSENT), null)));
        assertEquals(Status.HOLD_INVALID_OR_MISSING_TIME, p.items().getFirst().reviewStatus());
    }
    @Test void missingCatalogueOrCheckpointMemberInvalidatesWholeSnapshot() {
        var r = record(1); var all = List.of(r);
        invalid(() -> planner.plan("test", EPOCH, NOW, NOW, head(all), all, List.of(), Map.of()));
        invalid(() -> planner.plan("test", EPOCH, NOW, NOW, head(List.of()), all, all, Map.of()));
    }
    @Test void changedIdentityWithSameCountIsRejected() {
        var all = List.of(record(1)); var other = List.of(record(2));
        invalid(() -> planner.plan("test", EPOCH, NOW, NOW, head(all), other, other, Map.of()));
    }
    @Test void wrongReceiptIdentityAndGenerationAreRejected() {
        var r = record(1); var other = record(2);
        invalid(() -> plan(List.of(r), Map.of(r.objectKey(), proof(other, EnumSet.allOf(Requirement.class)))));
        var receipt = ErasureCompletion.observed(r, new UUID(0, 2).toString(), ABSENT);
        invalid(() -> plan(List.of(r), Map.of(r.objectKey(), new Proof(receipt, new Evidence(ABSENT, NOW, Set.of())))));
    }
    @Test void evidenceCannotBorrowAnEarlierAbsenceDate() {
        var r = record(1);
        invalid(() -> plan(List.of(r), Map.of(r.objectKey(), new Proof(ErasureCompletion.observed(r, EPOCH, ABSENT),
                new Evidence(ABSENT.minusSeconds(1), NOW, EnumSet.allOf(Requirement.class))))));
    }
    @Test void staleSnapshotFutureEvidenceAndForeignRealmAreRejected() {
        var r = record(1); var all = List.of(r);
        invalid(() -> planner.plan("test", EPOCH, NOW, NOW.minusSeconds(601), head(all), all, all, Map.of()));
        invalid(() -> planner.plan("production", EPOCH, NOW, NOW, head(all), all, all, Map.of()));
        invalid(() -> plan(all, Map.of(r.objectKey(), new Proof(ErasureCompletion.observed(r, EPOCH, ABSENT),
                new Evidence(ABSENT, NOW.plusSeconds(1), Set.of())))));
    }
    @Test void staleReviewEvidenceIsAnExplicitHold() {
        var r = record(1);
        var p = plan(List.of(r), Map.of(r.objectKey(), new Proof(ErasureCompletion.observed(r, EPOCH, ABSENT),
                new Evidence(ABSENT, NOW.minusSeconds(86401), EnumSet.allOf(Requirement.class)))));
        assertEquals(Status.HOLD_STALE_EVIDENCE, p.items().getFirst().reviewStatus());
    }
    @Test void extraProofCannotHideAnIncompleteSnapshot() {
        invalid(() -> plan(List.of(), Map.of(record(1).objectKey(), proof(record(1), Set.of()))));
    }
    @Test void planDigestIgnoresListingOrderButBindsEvidenceAndRevision() {
        var a = record(1); var b = record(2); var records = List.of(a, b);
        var p = plan(records, Map.of());
        assertEquals(p.planDigest(), plan(List.of(b, a), Map.of()).planDigest());
        assertNotEquals(p.planDigest(), plan(records, Map.of(a.objectKey(), proof(a, Set.of()))).planDigest());
        var cp = head(records);
        assertNotEquals(p.planDigest(), planner.plan("test", EPOCH, NOW, NOW,
                new CheckpointedErasureLedger.Head("revision-2", cp.checkpoint()), records, records, Map.of()).planDigest());
    }
    @Test void completionCannotPredateEligibilityOrLieInFuture() {
        var r = record(1);
        for (var at : List.of(ABSENT.minusSeconds(1), NOW.plusSeconds(1)))
            invalid(() -> plan(List.of(r), Map.of(r.objectKey(), new Proof(
                    ErasureCompletion.observed(r, EPOCH, at), new Evidence(at, NOW, Set.of())))));
    }
    @Test void planExpiresNoLaterThanItsEvidence() {
        var r = record(1);
        var p = plan(List.of(r), Map.of(r.objectKey(), new Proof(ErasureCompletion.observed(r, EPOCH, ABSENT),
                new Evidence(ABSENT, NOW.minusSeconds(86400), EnumSet.allOf(Requirement.class)))));
        assertEquals(1, p.candidates()); assertEquals(NOW, p.validUntil());
    }
    @Test void futureSnapshotAndWrongExpectedEpochAreRejected() {
        var all = List.of(record(1));
        invalid(() -> planner.plan("test", EPOCH, NOW, NOW.plusSeconds(1), head(all), all, all, Map.of()));
        invalid(() -> planner.plan("test", new UUID(0, 2).toString(), NOW, NOW, head(all), all, all, Map.of()));
    }
    @Test void duplicateIdentityIsNotASecondRetirementTarget() {
        var a = record(1);
        var b = new ErasureRecord(1, a.realm(), a.userId(), a.userCreatedAt(), new UUID(0, 2).toString(), a.eligibleAt());
        var all = List.of(a, b);
        invalid(() -> plan(all, Map.of()));
    }
    @Test void oneNanosecondBeforeRetentionBoundaryIsHeld() {
        var r = record(1); var at = NOW.minusNanos(1); var all = List.of(r);
        var p = planner.plan("test", EPOCH, at, at, head(all), all, all,
                Map.of(r.objectKey(), new Proof(ErasureCompletion.observed(r, EPOCH, ABSENT),
                        new Evidence(ABSENT, at, EnumSet.allOf(Requirement.class)))));
        assertEquals(0, p.candidates()); assertEquals(Status.HOLD_MINIMUM_DELAY, p.items().getFirst().reviewStatus());
    }
}
