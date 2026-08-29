package com.example.toiletbatch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RestroomSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RestroomSyncScheduler.class);

    private final RestroomSyncExecutionService restroomSyncExecutionService;

    public RestroomSyncScheduler(RestroomSyncExecutionService restroomSyncExecutionService) {
        this.restroomSyncExecutionService = restroomSyncExecutionService;
    }

    @Scheduled(cron = "${batch.restroom-sync.cron}", zone = "${batch.restroom-sync.zone}")
    public void synchronizeDaily() {
        try {
            RestroomSyncResult result = restroomSyncExecutionService.synchronizeRecentUpdates(BatchSyncTrigger.SCHEDULED);
            log.info(
                    "공중화장실 동기화 완료: range=[{}, {}), pages={}, received={}, inserted={}, updated={}, skipped={}",
                    result.fromInclusive(), result.toExclusive(), result.requestedPages(), result.receivedRecords(),
                    result.insertedRecords(), result.updatedRecords(), result.skippedRecords()
            );
        } catch (RuntimeException exception) {
            log.error("공중화장실 일일 동기화에 실패했습니다.", exception);
        }
    }
}
