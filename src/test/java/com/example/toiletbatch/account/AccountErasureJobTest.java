package com.example.toiletbatch.account;

import com.example.toiletbatch.batch.BatchFailureNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountErasureJobTest {
    private final AccountErasureWorker worker = mock(AccountErasureWorker.class);
    private final BatchFailureNotifier notifier = mock(BatchFailureNotifier.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @Test void disabledDoesNotTouchDatabaseOrRedis() {
        new AccountErasureJob(worker, notifier, metrics, false, 5000, false).runAfterSync();
        new AccountErasureJob(worker, notifier, metrics, true, 5000, true).runAfterSync();
        verifyNoInteractions(worker, notifier);
    }

    @Test void failureContinuesWithKeysetAndRetriesOnNextInvocation() {
        when(worker.findDue(any(), eq(0L), eq(50))).thenReturn(List.of(1L, 2L));
        when(worker.findDue(any(), eq(2L), eq(50))).thenReturn(List.of());
        when(worker.eraseIfDue(eq(1L), any())).thenThrow(new IllegalStateException("private data"));
        when(worker.eraseIfDue(eq(2L), any())).thenReturn(true);
        when(worker.countOverdue(any())).thenReturn(1L);
        var job = new AccountErasureJob(worker, notifier, metrics, true, 5000, false);
        job.runAfterSync();
        verify(worker).recordFailure(eq(1L), any(LocalDateTime.class));
        verify(worker).eraseIfDue(eq(2L), any());
        verify(notifier).notifyAccountErasureFailure(1, 1, false);
        assertEquals(1, metrics.counter("account.erasure.completed").count());
        assertEquals(1, metrics.counter("account.erasure.failed").count());
        job.runAfterSync();
        verify(worker, times(2)).findDue(any(), eq(0L), eq(50));
    }

    @Test void runLimitDoesNotSilentlyLoseBacklog() {
        when(worker.findDue(any(), eq(0L), eq(2))).thenReturn(List.of(1L, 2L));
        when(worker.eraseIfDue(anyLong(), any())).thenReturn(true);
        when(worker.countOverdue(any())).thenReturn(5L);
        new AccountErasureJob(worker, notifier, metrics, true, 2, false).runAfterSync();
        verify(worker, times(1)).findDue(any(), anyLong(), anyInt());
        verify(notifier).notifyAccountErasureFailure(0, 5, false);
    }

    @Test void infrastructureAndNotificationFailuresDoNotEscapeOrKeepJobLocked() {
        when(worker.findDue(any(), anyLong(), anyInt())).thenThrow(new IllegalStateException("secret"));
        doThrow(new IllegalStateException("webhook")).when(notifier).notifyAccountErasureFailure(anyInt(), anyLong(), anyBoolean());
        var job = new AccountErasureJob(worker, notifier, metrics, true, 50, false);
        assertDoesNotThrow(job::runAfterSync);
        assertDoesNotThrow(job::runAfterSync);
        verify(notifier, times(2)).notifyAccountErasureFailure(0, 0, true);
    }

    @Test void checkpointFailureDoesNotBlockNextAccount() {
        when(worker.findDue(any(), eq(0L), eq(2))).thenReturn(List.of(1L, 2L));
        when(worker.eraseIfDue(eq(1L), any())).thenThrow(new IllegalStateException());
        doThrow(new IllegalStateException()).when(worker).recordFailure(eq(1L), any());
        when(worker.eraseIfDue(eq(2L), any())).thenReturn(true);
        assertDoesNotThrow(new AccountErasureJob(worker, notifier, metrics, true, 2, false)::runAfterSync);
        verify(worker).eraseIfDue(eq(2L), any());
    }
    @Test void globalLedgerFailureStopsFurtherExternalRequestsAndAlerts() {
        when(worker.findDue(any(), eq(0L), eq(2))).thenReturn(List.of(1L, 2L));
        when(worker.eraseIfDue(eq(1L), any())).thenThrow(new IllegalStateException("ERASURE_LEDGER_UNAVAILABLE"));
        when(worker.countOverdue(any())).thenReturn(2L);
        new AccountErasureJob(worker, notifier, metrics, true, 2, false).runAfterSync();
        verify(worker, never()).eraseIfDue(eq(2L), any());
        verify(notifier).notifyAccountErasureFailure(1, 2, true);
    }
}
