package com.example.toiletbatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RestroomSyncExecutionServiceTest {

    @Mock
    private RestroomSyncService restroomSyncService;

    @Mock
    private BatchSyncHistoryRepository historyRepository;

    @Test
    void savesCompletedScheduledExecutionWithTotalSnapshot() {
        RestroomSyncResult result = new RestroomSyncResult(
                LocalDateTime.of(2026, 8, 26, 0, 0), LocalDateTime.of(2026, 8, 29, 0, 0),
                2, 103, 22, 81, 0
        );
        when(restroomSyncService.synchronize(any(), any())).thenReturn(result);
        when(historyRepository.countToilets()).thenReturn(12_345L);

        RestroomSyncResult actual = serviceAt("2026-08-29T02:00:00Z")
                .synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED);

        assertEquals(result, actual);
        verify(historyRepository).recordSuccess(
                eq(BatchSyncTrigger.SCHEDULED), eq(result), eq(12_345L), any(), any()
        );
    }

    @Test
    void savesFailureWithTheScheduledRangeThenRethrows() {
        RuntimeException exception = new IllegalStateException("공공데이터 API 호출 실패");
        when(restroomSyncService.synchronize(any(), any())).thenThrow(exception);
        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        assertThrows(IllegalStateException.class, () -> serviceAt("2026-08-29T02:00:00Z")
                .synchronizeRecentUpdates(BatchSyncTrigger.MANUAL));

        verify(historyRepository).recordFailure(
                eq(BatchSyncTrigger.MANUAL), fromCaptor.capture(), toCaptor.capture(), any(), any(), eq(exception)
        );
        assertEquals(LocalDateTime.of(2026, 8, 26, 0, 0), fromCaptor.getValue());
        assertEquals(LocalDateTime.of(2026, 8, 29, 0, 0), toCaptor.getValue());
    }

    private RestroomSyncExecutionService serviceAt(String instant) {
        RestroomSyncProperties properties = new RestroomSyncProperties("0 0 2 * * *", "Asia/Seoul", 3, 3);
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Seoul"));
        return new RestroomSyncExecutionService(restroomSyncService, historyRepository, properties, clock);
    }
}
