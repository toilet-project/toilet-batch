package com.example.toiletbatch.account;

import com.example.toiletbatch.batch.BatchFailureNotifier;
import com.geupddong.account.RetirementMaintenanceRunner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountLifecyclePostJobTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final BatchFailureNotifier notifier=mock(BatchFailureNotifier.class);
    final AccountMaintenanceGuard guard=mock(AccountMaintenanceGuard.class);
    @Test void defaultsCannotReachDbLockOrNotifier() {
        var env=new MockEnvironment();
        new AccountErasureCompletionJob(env,jdbc,guard,notifier).runAfterErasure();
        new AccountLedgerRetirementJob(env,jdbc,notifier).runAfterCompletion();
        verifyNoInteractions(jdbc,guard,notifier);
    }
    @Test void maintenanceOverridesEnabledFlags() {
        var env=new MockEnvironment().withProperty("batch.account-erasure.enabled","true")
                .withProperty("ERASURE_RETIREMENT_WRITE_ENABLED","true").withProperty("account.lifecycle.maintenance","true");
        new AccountErasureCompletionJob(env,jdbc,guard,notifier).runAfterErasure();
        new AccountLedgerRetirementJob(env,jdbc,notifier).runAfterCompletion();
        verifyNoInteractions(jdbc,guard,notifier);
    }
    @Test void writeRequiresCompatibilityAcknowledgementBeforeAnyIo() {
        var env=new MockEnvironment().withProperty("ERASURE_RETIREMENT_WRITE_ENABLED","true");
        assertThrows(IllegalStateException.class,()->RetirementMaintenanceRunner.run(env,jdbc,true,false));
        verifyNoInteractions(jdbc);
    }
    @Test void misconfiguredEnabledJobNotifiesWithoutExposingSourceException() {
        var env=new MockEnvironment().withProperty("ERASURE_RETIREMENT_WRITE_ENABLED","true").withProperty("account.lifecycle.maintenance","false");
        new AccountLedgerRetirementJob(env,jdbc,notifier).runAfterCompletion();
        verify(notifier).notifyAccountErasureFailure(1,0,true);verifyNoInteractions(jdbc);
    }
}
