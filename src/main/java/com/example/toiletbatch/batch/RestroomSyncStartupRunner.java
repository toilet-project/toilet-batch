package com.example.toiletbatch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 운영 컨테이너 내부에서만 사용하는 일회성 동기화 실행기입니다.
 *
 * <p>{@code BATCH_RESTROOM_SYNC_RUN_ON_STARTUP=true}일 때만 최근 overlap-days 범위를 동기화한 뒤
 * 애플리케이션 컨텍스트를 닫습니다. HTTP 엔드포인트를 추가하지 않으므로 외부에서 실행할 수 없습니다.</p>
 */
@Component
@ConditionalOnProperty(name = "batch.restroom-sync.run-on-startup", havingValue = "true")
public class RestroomSyncStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RestroomSyncStartupRunner.class);

    private final RestroomSyncExecutionService restroomSyncExecutionService;
    private final ConfigurableApplicationContext applicationContext;

    public RestroomSyncStartupRunner(
            RestroomSyncExecutionService restroomSyncExecutionService,
            ConfigurableApplicationContext applicationContext
    ) {
        this.restroomSyncExecutionService = restroomSyncExecutionService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            RestroomSyncResult result = restroomSyncExecutionService.synchronizeRecentUpdates(BatchSyncTrigger.MANUAL);
            log.info(
                    "수동 공중화장실 동기화 완료: range=[{}, {}), pages={}, received={}, inserted={}, updated={}, skipped={}",
                    result.fromInclusive(), result.toExclusive(), result.requestedPages(), result.receivedRecords(),
                    result.insertedRecords(), result.updatedRecords(), result.skippedRecords()
            );
        } finally {
            applicationContext.close();
        }
    }
}
