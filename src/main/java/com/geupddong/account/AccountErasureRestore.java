package com.geupddong.account;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline restored DB only. Download/verify the complete ledger before invoking this service. */
public final class AccountErasureRestore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public AccountErasureRestore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }

    public Result replay(List<ErasureRecord> records, String realm, LocalDateTime now, boolean apply) {
        return transaction.execute(tx -> {
            var unique = new LinkedHashMap<Long, ErasureRecord>();
            for (var record : records) {
                if (!realm.equals(record.realm()) || LocalDateTime.parse(record.eligibleAt()).isAfter(now))
                    throw new IllegalStateException("ERASURE_RESTORE_INVALID_RECORD");
                var previous = unique.putIfAbsent(record.userId(), record);
                if (previous != null && !previous.userCreatedAt().equals(record.userCreatedAt()))
                    throw new IllegalStateException("ERASURE_RESTORE_IDENTITY_CONFLICT");
            }
            var ordered = unique.values().stream().sorted(Comparator.comparingLong(ErasureRecord::userId)).toList();
            int matched = 0, absent = 0;
            var targets = new java.util.ArrayList<Long>();
            // Validate all identities before changing ANY row. Apply locks in a stable order.
            for (var record : ordered) {
                var dates = jdbc.query("SELECT created_at FROM app_user WHERE user_id=?" + (apply ? " FOR UPDATE" : ""),
                        (rs, row) -> rs.getObject(1, LocalDateTime.class), record.userId());
                if (dates.isEmpty()) { absent++; continue; }
                if (!dates.getFirst().equals(LocalDateTime.parse(record.userCreatedAt())))
                    throw new IllegalStateException("ERASURE_RESTORE_IDENTITY_CONFLICT");
                targets.add(record.userId()); matched++;
            }
            if (apply) for (long id : targets) {
                // A backup taken BEFORE withdrawal can contain an ACTIVE member.
                jdbc.update("UPDATE app_user SET status='WITHDRAWN' WHERE user_id=?", id);
                AccountErasureSql.erase(jdbc, id);
            }
            return new Result(records.size(), matched, absent, apply ? matched : 0);
        });
    }
    public record Result(int records, int matched, int absent, int erased) { }
}
