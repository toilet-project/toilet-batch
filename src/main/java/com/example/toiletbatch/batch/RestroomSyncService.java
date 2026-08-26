package com.example.toiletbatch.batch;

import com.example.toiletbatch.publicdata.PublicRestroomApiClient;
import com.example.toiletbatch.publicdata.PublicRestroomPage;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RestroomSyncService {

    private static final Logger log = LoggerFactory.getLogger(RestroomSyncService.class);

    private final PublicRestroomApiClient apiClient;
    private final ToiletSyncWriter toiletSyncWriter;
    private final RestroomSyncProperties properties;
    private final Clock clock;
    private final ReentrantLock executionLock = new ReentrantLock();

    public RestroomSyncService(
            PublicRestroomApiClient apiClient,
            ToiletSyncWriter toiletSyncWriter,
            RestroomSyncProperties properties
    ) {
        this(apiClient, toiletSyncWriter, properties, Clock.system(properties.zoneId()));
    }

    RestroomSyncService(
            PublicRestroomApiClient apiClient,
            ToiletSyncWriter toiletSyncWriter,
            RestroomSyncProperties properties,
            Clock clock
    ) {
        this.apiClient = apiClient;
        this.toiletSyncWriter = toiletSyncWriter;
        this.properties = properties;
        this.clock = clock;
    }

    public RestroomSyncResult synchronizeRecentUpdates() {
        LocalDate today = LocalDate.now(clock);
        return synchronize(
                today.minusDays(properties.overlapDays()).atStartOfDay(),
                today.atStartOfDay()
        );
    }

    public RestroomSyncResult synchronize(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        if (!executionLock.tryLock()) {
            throw new IllegalStateException("공중화장실 동기화가 이미 실행 중입니다.");
        }

        try {
            int pageNumber = 1;
            int requestedPages = 0;
            int receivedRecords = 0;
            int insertedRecords = 0;
            int updatedRecords = 0;
            int skippedRecords = 0;

            while (true) {
                PublicRestroomPage page = fetchPageWithRetry(fromInclusive, toExclusive, pageNumber);
                requestedPages++;

                RestroomSyncWriteResult writeResult = toiletSyncWriter.upsertPage(page.records());
                receivedRecords += page.records().size();
                insertedRecords += writeResult.insertedRecords();
                updatedRecords += writeResult.updatedRecords();
                skippedRecords += writeResult.skippedRecords();

                if (page.records().isEmpty() || receivedRecords >= page.totalCount()) {
                    return new RestroomSyncResult(
                            fromInclusive, toExclusive, requestedPages, receivedRecords,
                            insertedRecords, updatedRecords, skippedRecords
                    );
                }
                pageNumber++;
            }
        } finally {
            executionLock.unlock();
        }
    }

    private PublicRestroomPage fetchPageWithRetry(
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            int pageNumber
    ) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                return apiClient.fetchPage(fromInclusive, toExclusive, pageNumber);
            } catch (RuntimeException exception) {
                lastException = exception;
                if (attempt == properties.maxAttempts()) {
                    break;
                }
                long delayMillis = attempt * 1_000L;
                log.warn("공공데이터 API {}페이지 조회 실패. {}ms 후 재시도합니다. ({}/{})", pageNumber, delayMillis, attempt, properties.maxAttempts());
                waitBeforeRetry(delayMillis);
            }
        }
        throw lastException;
    }

    private void waitBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("공공데이터 API 재시도 대기 중 인터럽트되었습니다.", exception);
        }
    }
}
