package com.example.toiletbatch.region;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Covers all writers: user-report approvals, direct admin corrections, imports and batch inserts. */
@Component
@ConditionalOnProperty(name = "batch.region.enabled", havingValue = "true")
public class RegionReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(RegionReconciliationScheduler.class);
    private final RegionRepository repository;
    public RegionReconciliationScheduler(DataSource dataSource) { repository = new RegionRepository(dataSource); }

    @Bean
    public static ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("restroom-sync-");
        return scheduler;
    }

    @Bean
    public static ThreadPoolTaskScheduler regionTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("region-reconciliation-");
        return scheduler;
    }

    @Scheduled(scheduler = "regionTaskScheduler", fixedDelayString = "${batch.region.delay-ms:300000}", initialDelayString = "${batch.region.initial-delay-ms:60000}")
    public void reconcile() {
        try {
            var report = RegionNormalizationCli.execute(repository, true, false);
            if (report.containsKey("stopped")) log.warn("Region reconciliation paused: {}", report);
            else log.info("Region reconciliation: {}", report);
        } catch (Exception e) {
            // JDBC/HTTP exception messages may contain source data or connection details.
            log.error("Region reconciliation failed; inspect DB/journal permissions. Error type={}", e.getClass().getSimpleName());
        }
    }
}
