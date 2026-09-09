package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;
import static com.example.toiletbatch.account.ErasureRetirementPlannerTest.*;

class ErasureRetirementTransitionTest {
    final ErasureRetirementPlannerTest fixture = new ErasureRetirementPlannerTest();
    final ErasureRetirementTransition verifier = new ErasureRetirementTransition();
    final ErasureRecord a = fixture.record(1), b = fixture.record(2);
    final List<ErasureRecord> all = List.of(a, b);
    final CheckpointedErasureLedger.Head head = fixture.head(all);
    final Map<String, ErasureRetirementPlanner.Proof> proofs = Map.of(
            a.objectKey(), fixture.proof(a, EnumSet.allOf(Requirement.class)),
            b.objectKey(), fixture.proof(b, EnumSet.allOf(Requirement.class)));
    Map<String, ErasureCompletion> receipts() {
        return Map.of(a.objectKey(), proofs.get(a.objectKey()).completion(), b.objectKey(), proofs.get(b.objectKey()).completion());
    }
    ErasureRetirementTransition.Prepared prepare() {
        return verifier.prepare("test", EPOCH, NOW, NOW, head, all, all, proofs, Set.of(a.objectKey()));
    }
    void invalid(Runnable action) {
        assertEquals("ERASURE_RETIREMENT_TRANSITION_INVALID",
                assertThrows(IllegalStateException.class, action::run).getMessage());
    }
    @Test void orderedRemovalAndExactRetryProduceExpectedSurvivorHash() {
        var prepared = prepare();
        var zero = verifier.observe(prepared, null, NOW, head, all, all, receipts());
        var one = verifier.observe(prepared, zero, NOW, head, List.of(b), all, receipts());
        var retry = verifier.observe(prepared, one, NOW, head, List.of(b), all, receipts());
        var two = verifier.observe(prepared, retry, NOW, head, List.of(b), List.of(b), receipts());
        var three = verifier.observe(prepared, two, NOW, head, List.of(b), List.of(b), Map.of(b.objectKey(), receipts().get(b.objectKey())));
        assertFalse(two.removalComplete()); assertTrue(three.removalComplete());
        assertEquals(1, three.remainingCount());
        assertEquals(head.checkpoint().inventoryDigest(Map.of(b.objectKey(), ErasureCompletion.digest(b))), three.remainingInventoryDigest());
    }
    @Test void partialRemovalCannotProduceCompletionProposal() {
        var observation = verifier.observe(prepare(), null, NOW, head, all, all, receipts());
        invalid(observation::remainingCount); invalid(observation::remainingInventoryDigest);
    }
    @Test void noWorkIsNotAnApprovedDeletion() {
        invalid(() -> verifier.prepare("test", EPOCH, NOW, NOW, head, all, all, proofs, Set.of()));
    }
    @Test void unknownTargetRejected() {
        invalid(() -> verifier.prepare("test", EPOCH, NOW, NOW, head, all, all, proofs, Set.of("unknown")));
    }
    @Test void heldCandidateCannotBeSelected() {
        var partial = Map.of(a.objectKey(), fixture.proof(a, Set.of()), b.objectKey(), proofs.get(b.objectKey()));
        invalid(() -> verifier.prepare("test", EPOCH, NOW, NOW, head, all, all, partial, Set.of(a.objectKey())));
    }
    @Test void sourceSnapshotRevalidated() {
        invalid(() -> verifier.prepare("test", EPOCH, NOW, NOW, head, all, List.of(a), proofs, Set.of(a.objectKey())));
    }
    @Test void nonTargetIntentCannotDisappear() {
        invalid(() -> verifier.observe(prepare(), null, NOW, head, List.of(a), all, receipts()));
    }
    @Test void nonTargetCatalogueCannotDisappear() {
        invalid(() -> verifier.observe(prepare(), null, NOW, head, all, List.of(a), receipts()));
    }
    @Test void nonTargetCompletionCannotDisappear() {
        invalid(() -> verifier.observe(prepare(), null, NOW, head, all, all, Map.of(a.objectKey(), receipts().get(a.objectKey()))));
    }
    @Test void extraOrDuplicateIntentRejected() {
        invalid(() -> verifier.observe(prepare(), null, NOW, head, List.of(a, b, fixture.record(3)), all, receipts()));
        invalid(() -> verifier.observe(prepare(), null, NOW, head, List.of(a, b, b), all, receipts()));
    }
    @Test void mutatedReceiptRejected() {
        var changed = new HashMap<>(receipts());
        changed.put(a.objectKey(), ErasureCompletion.observed(a, EPOCH, ABSENT.plusSeconds(1)));
        invalid(() -> verifier.observe(prepare(), null, NOW, head, all, all, changed));
    }
    @Test void wrongOrderRejected() {
        invalid(() -> verifier.observe(prepare(), null, NOW, head, all, List.of(b), receipts()));
        invalid(() -> verifier.observe(prepare(), null, NOW, head, List.of(b), all, Map.of(b.objectKey(), receipts().get(b.objectKey()))));
    }
    @Test void reappearanceRejected() {
        var prepared = prepare();
        var partial = verifier.observe(prepared, null, NOW, head, List.of(b), all, receipts());
        invalid(() -> verifier.observe(prepared, partial, NOW, head, all, all, receipts()));
    }
    @Test void crossPlanObservationRejected() {
        var oldPlan = prepare();
        var previous = verifier.observe(oldPlan, null, NOW, head, all, all, receipts());
        invalid(() -> verifier.observe(prepare(), previous, NOW, head, all, all, receipts()));
    }
    @Test void changedCheckpointRevisionRejected() {
        var changed = new CheckpointedErasureLedger.Head("different-revision", head.checkpoint());
        invalid(() -> verifier.observe(prepare(), null, NOW, changed, all, all, receipts()));
    }
    @Test void timeExpiryAndRegressionRejected() {
        var prepared = prepare();
        invalid(() -> verifier.observe(prepared, null, NOW.plusSeconds(601), head, all, all, receipts()));
        invalid(() -> verifier.observe(prepared, null, NOW.minusSeconds(1), head, all, all, receipts()));
        var previous = verifier.observe(prepared, null, NOW.plusSeconds(2), head, all, all, receipts());
        invalid(() -> verifier.observe(prepared, previous, NOW.plusSeconds(1), head, all, all, receipts()));
    }
    @Test void allSelectedCanReachEmptyVerifiedInventory() {
        var prepared = verifier.prepare("test", EPOCH, NOW, NOW, head, all, all, proofs, Set.of(a.objectKey(), b.objectKey()));
        var done = verifier.observe(prepared, null, NOW, head, List.of(), List.of(), Map.of());
        assertTrue(done.removalComplete()); assertEquals(0, done.remainingCount());
        assertEquals(head.checkpoint().inventoryDigest(Map.of()), done.remainingInventoryDigest());
    }
    @Test void summariesDoNotExposeIdentitiesOrClaimExecutionAuthorization() {
        var prepared = prepare();
        var observation = verifier.observe(prepared, null, NOW, head, all, all, receipts());
        assertTrue(prepared.toString().contains("executionAllowed=false"));
        assertFalse(prepared.toString().contains(a.withdrawalKey()));
        assertFalse(observation.toString().contains(a.objectKey()));
    }
}
