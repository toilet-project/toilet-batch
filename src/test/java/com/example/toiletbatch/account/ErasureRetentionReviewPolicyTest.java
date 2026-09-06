package com.example.toiletbatch.account;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;

class ErasureRetentionReviewPolicyTest {
    private final ErasureRetentionReviewPolicy policy = new ErasureRetentionReviewPolicy();
    private static final Instant ABSENT = Instant.parse("2026-09-06T12:00:00Z");
    private static final Instant REVIEW = ABSENT.plus(Duration.ofDays(32));
    private static Set<Requirement> all() { return EnumSet.allOf(Requirement.class); }

    @Test void allEvidenceAtExactBoundaryOnlyCreatesAReviewCandidate() {
        var decision = policy.evaluate(REVIEW, new Evidence(ABSENT, REVIEW, all()));
        assertEquals(Status.REVIEW_CANDIDATE, decision.status());
        assertEquals(REVIEW, decision.earliestReviewAt());
        assertTrue(decision.missing().isEmpty());
    }

    @Test void oneNanosecondBeforeBoundaryIsHeld() {
        var now = REVIEW.minusNanos(1);
        assertEquals(Status.HOLD_MINIMUM_DELAY,
                policy.evaluate(now, new Evidence(ABSENT, now, all())).status());
    }

    @Test void delayedSqlDeletionStartsANewWindowRegardlessOfIntentAge() {
        // Intent existed at ABSENT, SQL only committed 60 days later. Object age is not an input.
        var lateAbsence = ABSENT.plus(Duration.ofDays(60));
        var now = lateAbsence.plus(Duration.ofDays(1));
        var result = policy.evaluate(now, new Evidence(lateAbsence, now, all()));
        assertEquals(Status.HOLD_MINIMUM_DELAY, result.status());
        assertEquals(lateAbsence.plus(Duration.ofDays(32)), result.earliestReviewAt());
    }

    @ParameterizedTest @EnumSource(Requirement.class)
    void everyMissingConfirmationBlocksReview(Requirement missing) {
        var verified = all();
        verified.remove(missing);
        var result = policy.evaluate(REVIEW, new Evidence(ABSENT, REVIEW, verified));
        assertEquals(Status.HOLD_MISSING_EVIDENCE, result.status());
        assertEquals(Set.of(missing), result.missing());
    }

    @Test void absentEvidenceIsNotSuccess() {
        assertEquals(Status.HOLD_INVALID_OR_MISSING_TIME, policy.evaluate(REVIEW, null).status());
        assertEquals(Status.HOLD_INVALID_OR_MISSING_TIME,
                policy.evaluate(REVIEW, new Evidence(null, REVIEW, all())).status());
    }

    @Test void unknownConfirmationsRemainExplicitlyMissing() {
        var result = policy.evaluate(REVIEW, new Evidence(ABSENT, REVIEW, null));
        assertEquals(Status.HOLD_MISSING_EVIDENCE, result.status());
        assertEquals(all(), result.missing());
    }

    @Test void staleEvidenceIsHeldEvenAfterTheMinimumWindow() {
        assertEquals(Status.HOLD_STALE_EVIDENCE,
                policy.evaluate(REVIEW, new Evidence(ABSENT,
                        REVIEW.minus(Duration.ofHours(24)).minusNanos(1), all())).status());
    }

    @Test void exactly24HoursOldEvidenceIsAllowedForReviewNotExecution() {
        assertEquals(Status.REVIEW_CANDIDATE,
                policy.evaluate(REVIEW, new Evidence(ABSENT,
                        REVIEW.minus(Duration.ofHours(24)), all())).status());
    }

    @Test void futureChecksAndChecksBeforeAbsenceAreInvalid() {
        assertEquals(Status.HOLD_INVALID_OR_MISSING_TIME,
                policy.evaluate(REVIEW, new Evidence(ABSENT, REVIEW.plusSeconds(1), all())).status());
        assertEquals(Status.HOLD_INVALID_OR_MISSING_TIME,
                policy.evaluate(REVIEW, new Evidence(ABSENT, ABSENT.minusSeconds(1), all())).status());
    }

    @Test void overflowCannotCreateAnEligibleDate() {
        assertEquals(Status.HOLD_INVALID_OR_MISSING_TIME,
                policy.evaluate(Instant.MAX, new Evidence(Instant.MAX, Instant.MAX, all())).status());
    }

    @Test void confirmationSetsAreImmutableSnapshots() {
        var mutable = all();
        var evidence = new Evidence(ABSENT, REVIEW, mutable);
        mutable.clear();
        assertEquals(Status.REVIEW_CANDIDATE, policy.evaluate(REVIEW, evidence).status());
        var held = policy.evaluate(REVIEW, new Evidence(ABSENT, REVIEW, Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> held.missing().clear());
    }
}
