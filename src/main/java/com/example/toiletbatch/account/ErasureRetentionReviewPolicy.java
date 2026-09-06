package com.example.toiletbatch.account;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure dry-run policy, NOT a deletion authorization or an evidence collector.
 * No Spring bean, scheduler, SQL, filesystem or R2 calls. Never use object age
 * as the start of retention: an intent can precede a successful erasure by weeks.
 */
public final class ErasureRetentionReviewPolicy {
    public static final Duration MINIMUM_REVIEW_DELAY = Duration.ofDays(32);
    public static final Duration MAX_EVIDENCE_AGE = Duration.ofHours(24);

    public enum Requirement {
        // Verified policy v1: dumps 14 days, binlogs 30 days; settings alone do not suffice.
        RETENTION_POLICY_V1_AND_ENFORCEMENT_VERIFIED,
        ALL_COPY_LOCATIONS_INVENTORIED,
        PRE_ERASURE_COPIES_AND_LOGS_REMOVED,
        DATABASE_ABSENCE_RECONFIRMED,
        NO_RESTORE_SINCE_ABSENCE_CONFIRMATION,
        NO_IN_FLIGHT_BACKUP_OR_RESTORE,
        LEDGER_INDEPENDENT_INVENTORY_VERIFIED,
        KEY_RECOVERY_VERIFIED
    }

    /** All confirmations must refer to the same identity, realm and consistent inventory. */
    public record Evidence(Instant firstConfirmedAbsentAt, Instant checkedAt,
                           Set<Requirement> verified) {
        public Evidence {
            verified = verified == null ? Set.of() : Set.copyOf(verified);
        }
    }

    public enum Status {
        HOLD_INVALID_OR_MISSING_TIME,
        HOLD_STALE_EVIDENCE,
        HOLD_MINIMUM_DELAY,
        HOLD_MISSING_EVIDENCE,
        REVIEW_CANDIDATE
    }

    /** REVIEW_CANDIDATE still requires fresh guarded verification before any future deletion. */
    public record Decision(Status status, Instant earliestReviewAt, Set<Requirement> missing) {
        public Decision { missing = Set.copyOf(missing); }
    }

    public Decision evaluate(Instant now, Evidence evidence) {
        var missing = EnumSet.allOf(Requirement.class);
        if (evidence != null) missing.removeAll(evidence.verified());
        if (now == null || evidence == null || evidence.firstConfirmedAbsentAt() == null
                || evidence.checkedAt() == null
                || evidence.firstConfirmedAbsentAt().isAfter(evidence.checkedAt())
                || evidence.checkedAt().isAfter(now)) {
            return new Decision(Status.HOLD_INVALID_OR_MISSING_TIME, null, missing);
        }
        Instant earliest;
        try {
            earliest = evidence.firstConfirmedAbsentAt().plus(MINIMUM_REVIEW_DELAY);
        } catch (java.time.DateTimeException | ArithmeticException invalid) {
            return new Decision(Status.HOLD_INVALID_OR_MISSING_TIME, null, missing);
        }
        if (Duration.between(evidence.checkedAt(), now).compareTo(MAX_EVIDENCE_AGE) > 0)
            return new Decision(Status.HOLD_STALE_EVIDENCE, earliest, missing);
        if (now.isBefore(earliest))
            return new Decision(Status.HOLD_MINIMUM_DELAY, earliest, missing);
        if (!missing.isEmpty())
            return new Decision(Status.HOLD_MISSING_EVIDENCE, earliest, missing);
        return new Decision(Status.REVIEW_CANDIDATE, earliest, missing);
    }
}
