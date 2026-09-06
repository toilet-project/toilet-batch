package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit maintenance reconciliation: SELECT-only DB access, optional R2 completion writes. */
public final class AccountErasureEvidenceCollector {
    public interface CompletionStore {
        ErasureCompletion read(ErasureCompletion expected);
        ErasureCompletion writeOnce(ErasureCompletion proposed);
    }
    public record Result(int records, int pending, int absent, int confirmed, int wouldRecord,
                         int confirmationsWithUnresolvedBackups) { }
    private final JdbcTemplate jdbc;
    private final CompletionStore completions;
    private final Clock clock;

    public AccountErasureEvidenceCollector(JdbcTemplate jdbc, CompletionStore completions, Clock clock) {
        this.jdbc = jdbc; this.completions = completions; this.clock = clock;
    }

    public Result collect(List<ErasureRecord> records, ErasureIntentInventory inventory,
                          BackupEvidenceInventory backups, boolean recordCompletions) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("ERASURE_EVIDENCE_ACTIVE_TRANSACTION");
        inventory.verify(records); // Full independent inventory check BEFORE any R2 write.
        Instant cutoff = clock.instant();
        int pending = 0;
        var absent = new ArrayList<ErasureRecord>();
        // Preflight ALL identities; a wrong DB identity never produces partial acknowledgements.
        for (var record : records) {
            if (LocalDateTime.parse(record.eligibleAt()).atZone(ZoneId.of("Asia/Seoul")).toInstant().isAfter(cutoff)
                    || !isAbsent(record)) { pending++; continue; }
            absent.add(record);
        }
        int confirmed = 0, wouldRecord = 0, unresolved = 0;
        for (var record : absent) {
            // Required operational guard stops writers/restores; still recheck immediately before writing.
            if (!isAbsent(record)) throw new IllegalStateException("ERASURE_EVIDENCE_DATABASE_CHANGED");
            Instant observedAt = clock.instant();
            if (observedAt.isBefore(cutoff)) throw new IllegalStateException("ERASURE_EVIDENCE_CLOCK_REVERSED");
            var proposed = ErasureCompletion.observed(record, inventory.databaseEpoch(), observedAt);
            var receipt = completions.read(proposed);
            if (receipt == null && recordCompletions) {
                if (!isAbsent(record)) throw new IllegalStateException("ERASURE_EVIDENCE_DATABASE_CHANGED");
                receipt = completions.writeOnce(proposed);
            }
            if (receipt == null) { wouldRecord++; continue; }
            if (!receipt.objectKey().equals(proposed.objectKey()) || !receipt.intentDigest().equals(proposed.intentDigest())
                    || Instant.parse(receipt.firstConfirmedAbsentAt()).isBefore(
                        LocalDateTime.parse(record.eligibleAt()).atZone(ZoneId.of("Asia/Seoul")).toInstant())
                    || Instant.parse(receipt.firstConfirmedAbsentAt()).isAfter(clock.instant()))
                throw new IllegalStateException("ERASURE_EVIDENCE_COMPLETION_INVALID");
            confirmed++;
            // A local dump scan cannot attest binlogs, snapshots or all copy locations.
            if (!backups.compare(Instant.parse(receipt.firstConfirmedAbsentAt())).allCopiesCleared()) unresolved++;
        }
        return new Result(records.size(), pending, absent.size(), confirmed, wouldRecord, unresolved);
    }

    private boolean isAbsent(ErasureRecord record) {
        List<LocalDateTime> matches = jdbc.query("SELECT created_at FROM app_user WHERE user_id=?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), record.userId());
        if (matches.size() > 1 || (!matches.isEmpty()
                && !matches.getFirst().equals(LocalDateTime.parse(record.userCreatedAt()))))
            throw new IllegalStateException("ERASURE_EVIDENCE_IDENTITY_CONFLICT");
        return matches.isEmpty();
    }
}
