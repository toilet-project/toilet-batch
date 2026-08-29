package com.example.toiletbatch.batch;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 동기화를 실행하고 성공·실패 이력을 같은 단위로 보관합니다. */
@Service
public class RestroomSyncExecutionService {

    private final RestroomSyncService restroomSyncService;
    private final BatchSyncHistoryRepository historyRepository;
    private final RestroomSyncProperties properties;
    private final Clock clock;

    @Autowired
    public RestroomSyncExecutionService(
            RestroomSyncService restroomSyncService,
            BatchSyncHistoryRepository historyRepository,
            RestroomSyncProperties properties
    ) {
        this(restroomSyncService, historyRepository, properties, Clock.system(properties.zoneId()));
    }

    RestroomSyncExecutionService(
            RestroomSyncService restroomSyncService,
            BatchSyncHistoryRepository historyRepository,
            RestroomSyncProperties properties,
            Clock clock
    ) {
        this.restroomSyncService = restroomSyncService;
        this.historyRepository = historyRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public RestroomSyncResult synchronizeRecentUpdates(BatchSyncTrigger trigger) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime fromInclusive = today.minusDays(properties.overlapDays()).atStartOfDay();
        LocalDateTime toExclusive = today.atStartOfDay();

        try {
            RestroomSyncResult result = restroomSyncService.synchronize(fromInclusive, toExclusive);
            historyRepository.recordSuccess(
                    trigger,
                    result,
                    historyRepository.countToilets(),
                    startedAt,
                    LocalDateTime.now(clock)
            );
            return result;
        } catch (RuntimeException exception) {
            historyRepository.recordFailure(
                    trigger,
                    fromInclusive,
                    toExclusive,
                    startedAt,
                    LocalDateTime.now(clock),
                    exception
            );
            throw exception;
        }
    }
}
