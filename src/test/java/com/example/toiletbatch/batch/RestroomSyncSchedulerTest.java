package com.example.toiletbatch.batch;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RestroomSyncSchedulerTest {

    private final RestroomSyncExecutionService executionService = mock(RestroomSyncExecutionService.class);
    private final BatchFailureNotifier failureNotifier = mock(BatchFailureNotifier.class);
    private final com.example.toiletbatch.account.AccountErasureJob erasure =
            mock(com.example.toiletbatch.account.AccountErasureJob.class);
    private final com.example.toiletbatch.account.AccountErasureCompletionJob completion = mock(com.example.toiletbatch.account.AccountErasureCompletionJob.class);
    private final com.example.toiletbatch.account.AccountLedgerRetirementJob retirement = mock(com.example.toiletbatch.account.AccountLedgerRetirementJob.class);
    private final RestroomSyncScheduler scheduler = new RestroomSyncScheduler(executionService, failureNotifier, erasure, completion, retirement);

    @Test
    void sendsNotificationAfterRecordedExecutionFails() {
        RuntimeException failure = new IllegalStateException("secret-bearing original message");
        when(executionService.synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED)).thenThrow(failure);

        scheduler.synchronizeDaily();

        verify(failureNotifier).notifyFailure(failure);
        var order = inOrder(executionService, failureNotifier, erasure);
        order.verify(executionService).synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED);
        order.verify(failureNotifier).notifyFailure(failure);
        order.verify(erasure).runAfterSync();
    }

    @Test
    void doesNotNotifyWhenExecutionSucceeds() {
        RestroomSyncResult result = new RestroomSyncResult(
                LocalDateTime.of(2026, 8, 28, 0, 0),
                LocalDateTime.of(2026, 8, 31, 0, 0),
                1, 3, 1, 2, 0
        );
        when(executionService.synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED)).thenReturn(result);

        scheduler.synchronizeDaily();

        verify(executionService).synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED);
        var order = inOrder(executionService, erasure, completion, retirement);
        order.verify(executionService).synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED);
        order.verify(erasure).runAfterSync();
        order.verify(completion).runAfterErasure();
        order.verify(retirement).runAfterCompletion();
    }

    @Test
    void notificationFailureStillStartsErasure() {
        var failure = new IllegalStateException("failure");
        when(executionService.synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED)).thenThrow(failure);
        doThrow(new IllegalStateException("notification")).when(failureNotifier).notifyFailure(failure);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, scheduler::synchronizeDaily);
        verify(erasure).runAfterSync();
        verify(completion).runAfterErasure();
        verify(retirement).runAfterCompletion();
    }
    @Test void erasureInterruptionStillAttemptsCompletionReconciliationAndRetirement() {
        doThrow(new IllegalStateException("synthetic")).when(erasure).runAfterSync();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,scheduler::synchronizeDaily);
        var order=inOrder(erasure,completion,retirement);
        order.verify(erasure).runAfterSync();order.verify(completion).runAfterErasure();order.verify(retirement).runAfterCompletion();
    }
    @Test void completionInterruptionStillAttemptsGuardedRetirement() {
        doThrow(new IllegalStateException("synthetic")).when(completion).runAfterErasure();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,scheduler::synchronizeDaily);
        verify(retirement).runAfterCompletion();
    }
}
