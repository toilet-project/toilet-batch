package com.example.toiletbatch.account;

import com.geupddong.account.AccountErasureSql;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One member per transaction, same lock order as API withdrawal/recovery: user, withdrawal. */
@Service
public class AccountErasureWorker {
    private final JdbcTemplate jdbc;
    private final AccountSessionCleaner sessions;
    private final TransactionTemplate transaction;

    public AccountErasureWorker(JdbcTemplate jdbc, AccountSessionCleaner sessions,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.transaction = new TransactionTemplate(transactions);
    }

    public List<Long> findDue(LocalDateTime cutoff, long afterId, int limit) {
        return jdbc.queryForList("SELECT user_id FROM account_withdrawal "
                + "WHERE next_attempt_at<=? AND purge_after<=? AND user_id>? ORDER BY user_id LIMIT ?",
                Long.class, cutoff, cutoff, afterId, limit);
    }

    public boolean eraseIfDue(long id, LocalDateTime cutoff) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var users = jdbc.queryForList("SELECT status FROM app_user WHERE user_id=? FOR UPDATE", String.class, id);
            if (users.isEmpty() || !"WITHDRAWN".equals(users.getFirst())) return false;
            var deadlines = jdbc.query("SELECT purge_after, next_attempt_at FROM account_withdrawal "
                    + "WHERE user_id=? FOR UPDATE",
                    (rs, row) -> new LocalDateTime[] {
                            rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime() }, id);
            if (deadlines.isEmpty() || deadlines.getFirst()[0].isAfter(cutoff)
                    || deadlines.getFirst()[1].isAfter(cutoff)) return false;
            // Redis is not in the SQL transaction. On failure keep DB data for a safe retry.
            sessions.clear(id);
            AccountErasureSql.erase(jdbc, id);
            return true;
        }));
    }

    public void recordFailure(long id, LocalDateTime now) {
        transaction.executeWithoutResult(status -> {
            var users = jdbc.queryForList("SELECT status FROM app_user WHERE user_id=? FOR UPDATE", String.class, id);
            if (users.isEmpty() || !"WITHDRAWN".equals(users.getFirst())) return;
            var attempts = jdbc.queryForList("SELECT attempts FROM account_withdrawal WHERE user_id=? FOR UPDATE",
                    Integer.class, id);
            if (attempts.isEmpty()) return;
            long delay = Math.min(60, 1L << Math.min(attempts.getFirst() + 1, 6));
            jdbc.update("UPDATE account_withdrawal SET attempts=attempts+1, next_attempt_at=?, "
                    + "last_failure_code='ERASURE_RETRY_REQUIRED' WHERE user_id=?", now.plusMinutes(delay), id);
        });
    }

    public long countOverdue(LocalDateTime now) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM account_withdrawal WHERE purge_after<=?", Long.class, now);
        return count == null ? 0 : count;
    }
}
