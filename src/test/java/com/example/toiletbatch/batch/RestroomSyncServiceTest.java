package com.example.toiletbatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletbatch.publicdata.PublicRestroomApiClient;
import com.example.toiletbatch.publicdata.PublicRestroomPage;
import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RestroomSyncServiceTest {

    @Mock
    private PublicRestroomApiClient apiClient;

    @Mock
    private ToiletSyncWriter toiletSyncWriter;

    @Test
    void synchronizesEveryPageAndAggregatesWriteResults() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 22, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 25, 0, 0);
        when(apiClient.fetchPage(from, to, 1)).thenReturn(new PublicRestroomPage(List.of(record("A"), record("B")), 1, 100, 3));
        when(apiClient.fetchPage(from, to, 2)).thenReturn(new PublicRestroomPage(List.of(record("C")), 2, 100, 3));
        when(toiletSyncWriter.upsertPage(any())).thenReturn(
                new RestroomSyncWriteResult(1, 1, 0),
                new RestroomSyncWriteResult(0, 1, 0)
        );

        RestroomSyncService service = serviceAt("2026-08-25T02:00:00Z");
        RestroomSyncResult result = service.synchronize(from, to);

        assertEquals(2, result.requestedPages());
        assertEquals(3, result.receivedRecords());
        assertEquals(1, result.insertedRecords());
        assertEquals(2, result.updatedRecords());
        assertEquals(0, result.skippedRecords());
        verify(apiClient).fetchPage(from, to, 1);
        verify(apiClient).fetchPage(from, to, 2);
        verify(toiletSyncWriter, times(2)).upsertPage(any());
    }

    @Test
    void synchronizesThreeDayOverlapInKoreaTime() {
        LocalDateTime expectedFrom = LocalDateTime.of(2026, 8, 22, 0, 0);
        LocalDateTime expectedTo = LocalDateTime.of(2026, 8, 25, 0, 0);
        when(apiClient.fetchPage(eq(expectedFrom), eq(expectedTo), eq(1)))
                .thenReturn(new PublicRestroomPage(List.of(), 1, 100, 0));
        when(toiletSyncWriter.upsertPage(List.of())).thenReturn(new RestroomSyncWriteResult(0, 0, 0));

        RestroomSyncResult result = serviceAt("2026-08-24T17:00:00Z").synchronizeRecentUpdates();

        assertEquals(expectedFrom, result.fromInclusive());
        assertEquals(expectedTo, result.toExclusive());
    }

    @Test
    void retriesTemporaryApiFailureBeforeWritingThePage() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 22, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 25, 0, 0);
        when(apiClient.fetchPage(from, to, 1))
                .thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(new PublicRestroomPage(List.of(), 1, 100, 0));
        when(toiletSyncWriter.upsertPage(List.of())).thenReturn(new RestroomSyncWriteResult(0, 0, 0));

        RestroomSyncResult result = serviceAt("2026-08-25T02:00:00Z").synchronize(from, to);

        assertEquals(1, result.requestedPages());
        verify(apiClient, times(2)).fetchPage(from, to, 1);
    }

    private RestroomSyncService serviceAt(String instant) {
        RestroomSyncProperties properties = new RestroomSyncProperties("0 0 2 * * *", "Asia/Seoul", 3, 3);
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Seoul"));
        return new RestroomSyncService(apiClient, toiletSyncWriter, properties, clock);
    }

    private PublicRestroomRecord record(String managementNumber) {
        return new PublicRestroomRecord(
                managementNumber, "테스트", "개방", "공중", "도로명", "지번", null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                "기관", "전화", "상시", "", "", "", "", "", "", "", "", ""
        );
    }
}
