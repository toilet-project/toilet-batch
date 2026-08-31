package com.example.toiletbatch.batch;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RestroomSyncSchedulerTest {

    private final RestroomSyncExecutionService executionService = mock(RestroomSyncExecutionService.class);
    private final BatchFailureNotifier failureNotifier = mock(BatchFailureNotifier.class);
    private final RestroomSyncScheduler scheduler = new RestroomSyncScheduler(executionService, failureNotifier);

    @Test
    void sendsNotificationAfterRecordedExecutionFails() {
        RuntimeException failure = new IllegalStateException("secret-bearing original message");
        when(executionService.synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED)).thenThrow(failure);

        scheduler.synchronizeDaily();

        verify(failureNotifier).notifyFailure(failure);
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
    }
}
