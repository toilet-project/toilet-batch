package com.example.toiletbatch.account;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic FK fixture on H2 or opt-in guarded native MySQL; separate from full-schema V11 validation. */
class AccountErasureWorkerTest {
    private JdbcTemplate jdbc;
    private AccountErasureWorker worker;
    private AccountSessionCleaner sessions;
    private com.geupddong.account.ErasureLedger ledger;
    private org.springframework.transaction.support.TransactionTemplate transaction;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 6, 2, 30);

    @BeforeEach void prepare() {
        var ds = NativeMySqlFixture.enabled() ? NativeMySqlFixture.create()
                : new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        sessions = mock(AccountSessionCleaner.class);
        ledger = mock(com.geupddong.account.ErasureLedger.class);
        var transactions = new DataSourceTransactionManager(ds);
        transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        worker = new AccountErasureWorker(jdbc, sessions, transactions, ledger, "production", true, false);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY, status VARCHAR(30), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE account_withdrawal(user_id BIGINT PRIMARY KEY, "
                + "purge_after TIMESTAMP, next_attempt_at TIMESTAMP, attempts INT DEFAULT 0, last_failure_code VARCHAR(50), withdrawal_key CHAR(36), "
                + "FOREIGN KEY(user_id) REFERENCES app_user(user_id))");
        jdbc.execute("CREATE TABLE toilet_report(report_id BIGINT PRIMARY KEY, reporter_user_id BIGINT, "
                + "reviewed_by_user_id BIGINT, reason VARCHAR(100), review_note VARCHAR(100), "
                + "active_request_key VARCHAR(100), proposed_latitude DECIMAL(10,7), "
                + "FOREIGN KEY(reporter_user_id) REFERENCES app_user(user_id), FOREIGN KEY(reviewed_by_user_id) REFERENCES app_user(user_id))");
        jdbc.execute("CREATE TABLE audit_log(actor_user_id BIGINT, actor_erased BOOLEAN DEFAULT FALSE, "
                + "target_type VARCHAR(50), target_id BIGINT, detail_json VARCHAR(100), FOREIGN KEY(actor_user_id) REFERENCES app_user(user_id))");
        jdbc.execute("CREATE TABLE coordinate_revision(applied_by_user_id BIGINT, FOREIGN KEY(applied_by_user_id) REFERENCES app_user(user_id))");
        jdbc.execute("CREATE TABLE coordinate_quality_review(reviewed_by_user_id BIGINT, review_note VARCHAR(100), FOREIGN KEY(reviewed_by_user_id) REFERENCES app_user(user_id))");
        jdbc.execute("CREATE TABLE user_role(user_id BIGINT, granted_by_user_id BIGINT, "
                + "FOREIGN KEY(user_id) REFERENCES app_user(user_id), FOREIGN KEY(granted_by_user_id) REFERENCES app_user(user_id))");
        for (String table : new String[]{"user_notification", "user_policy_consent", "user_social_account"})
            jdbc.execute("CREATE TABLE " + table + "(user_id BIGINT, FOREIGN KEY(user_id) REFERENCES app_user(user_id))");
        member(1, "WITHDRAWN", now);
        jdbc.update("INSERT INTO toilet_report VALUES(11,1,1,'contact','review','key',37.5)");
        jdbc.update("INSERT INTO audit_log VALUES(1,FALSE,'USER',1,'personal')");
        jdbc.update("INSERT INTO audit_log VALUES(NULL,FALSE,'TOILET_REPORT',11,'personal')");
        jdbc.update("INSERT INTO coordinate_revision VALUES(1)");
        jdbc.update("INSERT INTO coordinate_quality_review VALUES(1,'personal')");
        jdbc.update("INSERT INTO user_role VALUES(1,1)");
        for (String table : new String[]{"user_notification", "user_policy_consent", "user_social_account"})
            jdbc.update("INSERT INTO " + table + " VALUES(1)");
    }

    private void member(long id, String status, LocalDateTime due) {
        jdbc.update("INSERT INTO app_user(user_id,status) VALUES(?,?)", id, status);
        jdbc.update("INSERT INTO account_withdrawal(user_id,purge_after,next_attempt_at,withdrawal_key) VALUES(?,?,?,?)", id, due, due, UUID.randomUUID().toString());
    }

    @Test void erasesOnlyIdentityAndPreservesStructuredReport() {
        assertTrue(worker.eraseIfDue(1, now));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM toilet_report WHERE reporter_user_id IS NULL "
                + "AND reviewed_by_user_id IS NULL AND proposed_latitude=37.5", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE detail_json IS NOT NULL", Integer.class));
        verify(sessions).clear(1);
        var order = inOrder(ledger, sessions);
        order.verify(ledger).ensureRecorded(any());
        order.verify(sessions).clear(1);
        assertFalse(worker.eraseIfDue(1, now));
    }

    @Test void pausedDirectWorkerNeverTouchesDatabaseRedisOrLedger() {
        for (boolean maintenance : new boolean[]{false, true}) {
            var db = mock(JdbcTemplate.class);
            var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
            var stopped = new AccountErasureWorker(db, sessions, manager, ledger, "production", maintenance, maintenance);
            assertThrows(IllegalStateException.class, () -> stopped.eraseIfDue(1, now));
            verifyNoInteractions(db, manager, sessions, ledger);
        }
    }

    @Test void futureDeadlineRestoredAndLegacyAccountsAreNotErased() {
        member(2, "WITHDRAWN", now.plusSeconds(1));
        member(3, "ACTIVE", now.minusDays(1));
        jdbc.update("INSERT INTO app_user(user_id,status) VALUES(4,'WITHDRAWN')");
        assertFalse(worker.eraseIfDue(2, now));
        assertFalse(worker.eraseIfDue(3, now));
        assertFalse(worker.eraseIfDue(4, now));
        verifyNoInteractions(sessions);
    }

    @Test void sqlFailureRollsBackAllUnlinkingAndRecordsRetry() {
        jdbc.execute("CREATE TABLE unknown_reference(user_id BIGINT, FOREIGN KEY(user_id) REFERENCES app_user(user_id))");
        jdbc.update("INSERT INTO unknown_reference VALUES(1)");
        assertThrows(RuntimeException.class, () -> worker.eraseIfDue(1, now));
        assertEquals(1L, jdbc.queryForObject("SELECT reporter_user_id FROM toilet_report", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_social_account", Integer.class));
        worker.recordFailure(1, now);
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM account_withdrawal", Integer.class));
        assertTrue(worker.findDue(now, 0, 50).isEmpty());
        assertEquals(java.util.List.of(1L), worker.findDue(now.plusMinutes(2), 0, 50));
    }

    @Test void redisFailurePreservesDatabaseAndIsRetryable() {
        doThrow(new IllegalStateException()).when(sessions).clear(1);
        assertThrows(IllegalStateException.class, () -> worker.eraseIfDue(1, now));
        assertEquals(1L, jdbc.queryForObject("SELECT reporter_user_id FROM toilet_report", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account_withdrawal", Integer.class));
    }
    @Test void r2FailureDoesNotRemoveSessionsOrDatabaseRows() {
        doThrow(new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE")).when(ledger).ensureRecorded(any());
        assertThrows(IllegalStateException.class, () -> worker.eraseIfDue(1, now));
        verifyNoInteractions(sessions);
        assertEquals(1L, jdbc.queryForObject("SELECT reporter_user_id FROM toilet_report", Long.class));
    }

    @Test void keysetResumesWithoutSkippingRowsAfterDeletion() {
        member(2, "WITHDRAWN", now);
        member(3, "WITHDRAWN", now);
        assertEquals(java.util.List.of(1L, 2L), worker.findDue(now, 0, 2));
        worker.eraseIfDue(1, now);
        worker.eraseIfDue(2, now);
        assertEquals(java.util.List.of(3L), worker.findDue(now, 2, 2));
        assertEquals(1, worker.countOverdue(now));
    }

    @Test void recoveryHoldingUserLockWinsAndErasureRechecksCommittedState() throws Exception {
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
            transaction.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id=1 FOR UPDATE");
                future.set(executor.submit(() -> worker.eraseIfDue(1, now)));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> future.get().get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
                // Simulate API restore completing while owning the same lock.
                jdbc.update("UPDATE app_user SET status='ACTIVE' WHERE user_id=1");
                jdbc.update("DELETE FROM account_withdrawal WHERE user_id=1");
            });
            assertFalse(future.get().get(5, java.util.concurrent.TimeUnit.SECONDS));
            verifyNoInteractions(sessions);
            assertEquals(1L, jdbc.queryForObject("SELECT reporter_user_id FROM toilet_report", Long.class));
        }
    }
}
