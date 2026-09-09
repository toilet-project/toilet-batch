package com.example.toiletbatch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RestroomSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RestroomSyncScheduler.class);

    private final RestroomSyncExecutionService restroomSyncExecutionService;
    private final BatchFailureNotifier batchFailureNotifier;
    private final com.example.toiletbatch.account.AccountErasureJob accountErasureJob;
    private final com.example.toiletbatch.account.AccountErasureCompletionJob completionJob;
    private final com.example.toiletbatch.account.AccountLedgerRetirementJob retirementJob;

    public RestroomSyncScheduler(
            RestroomSyncExecutionService restroomSyncExecutionService,
            BatchFailureNotifier batchFailureNotifier,
            com.example.toiletbatch.account.AccountErasureJob accountErasureJob,
            com.example.toiletbatch.account.AccountErasureCompletionJob completionJob,
            com.example.toiletbatch.account.AccountLedgerRetirementJob retirementJob
    ) {
        this.restroomSyncExecutionService = restroomSyncExecutionService;
        this.batchFailureNotifier = batchFailureNotifier;
        this.accountErasureJob = accountErasureJob;
        this.completionJob = completionJob;
        this.retirementJob = retirementJob;
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
            batchFailureNotifier.notifyFailure(exception);
        } finally {
            // Only the scheduled daily chain invokes erasure; manual sync/analysis never does.
            // The job records its own failures and does not alter public-data sync history.
            try {accountErasureJob.runAfterSync();} finally {
                try {completionJob.runAfterErasure();} finally {retirementJob.runAfterCompletion();}
            }
        }
    }
}
