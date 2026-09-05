package com.example.toiletbatch.region;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionJobTest {
    @TempDir Path directory;
    @Test void dryRunNeverWritesDatabaseAndResumeDoesNotRepeatProviderCall() throws Exception {
        RegionRepository repository = mock(RegionRepository.class);
        RegionProvider provider = mock(RegionProvider.class);
        Source source = new Source(1, "대전 유성구 대학로", null, new BigDecimal("36.3"), new BigDecimal("127.3"));
        when(repository.count()).thenReturn(1L);
        when(repository.page(0, 100)).thenReturn(List.of(source)); when(repository.page(1, 100)).thenReturn(List.of());
        when(provider.reverse(source.point())).thenReturn(new Region("대전광역시", "30", "유성구", "30200", null, null, "3020012200", null));
        for (int pass = 0; pass < 2; pass++) {
            try (var journal = new RegionJournal(directory.resolve("dry-run.jsonl"))) {
                var job = new RegionJob(repository, new RegionNormalizer(provider, Clock.systemUTC()), journal, Clock.systemUTC());
                assertEquals(true, job.run(false, false, 10).get("scanComplete"));
            }
        }
        verify(provider, times(1)).reverse(source.point());
        verify(repository, never()).apply(any(), anyBoolean()); verify(repository, never()).lock();
    }
    @Test void quotaStopReturnsPartialReportWithoutDiscardingCompletedCheckpoint() throws Exception {
        RegionRepository repository = mock(RegionRepository.class);
        RegionProvider provider = mock(RegionProvider.class);
        Source source = new Source(1, null, null, BigDecimal.ONE, BigDecimal.TEN);
        when(repository.count()).thenReturn(1L); when(repository.page(0, 100)).thenReturn(List.of(source));
        when(provider.reverse(any())).thenThrow(new RegionProvider.Stop("BUDGET"));
        try (var journal = new RegionJournal(directory.resolve("stop.jsonl"))) {
            var report = new RegionJob(repository, new RegionNormalizer(provider, Clock.systemUTC()), journal, Clock.systemUTC()).run(false, false, 10);
            assertEquals("BUDGET", report.get("stopped")); assertNull(journal.get(1));
        }
        verify(repository, never()).apply(any(), anyBoolean());
    }
    @Test void missingCoordinatesAwaitAdministratorWithoutAnyProviderRequest() throws Exception {
        RegionRepository repository = mock(RegionRepository.class);
        RegionProvider provider = mock(RegionProvider.class);
        Source missing = new Source(1, "대전 유성구 대학로", null, null, null);
        Source partial = new Source(2, "대전 유성구 대학로", null, BigDecimal.ONE, null);
        when(repository.page(0, 100)).thenReturn(List.of(missing, partial));
        when(repository.page(2, 100)).thenReturn(List.of());
        try (var journal = new RegionJournal(directory.resolve("admin-coordinate.jsonl"))) {
            var job = new RegionJob(repository, new RegionNormalizer(provider, Clock.systemUTC()), journal, Clock.systemUTC());
            assertEquals(true, job.run(false, false, 10).get("scanComplete"));
            assertEquals(Status.NO_COORDINATE, journal.get(1).status());
            assertEquals("ADMIN_COORDINATE_REQUIRED", journal.get(1).reason());
            assertEquals(Status.INVALID_COORDINATE, journal.get(2).status());
        }
        verifyNoInteractions(provider);
        verify(repository, never()).apply(any(), anyBoolean());
    }
}
