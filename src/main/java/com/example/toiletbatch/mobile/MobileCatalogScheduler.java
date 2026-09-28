package com.example.toiletbatch.mobile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MobileCatalogScheduler {
    private static final Logger log = LoggerFactory.getLogger(MobileCatalogScheduler.class);
    private final MobileCatalogPublisher publisher;
    public MobileCatalogScheduler(MobileCatalogPublisher publisher) { this.publisher = publisher; }

    @Scheduled(cron = "${batch.mobile-catalog.cron:-}", zone = "Asia/Seoul")
    public void publishDaily() {
        try { log.info("Mobile catalog publication: {}", publisher.publish(false, List.of())); }
        catch (Exception failure) {
            // Do not log credential-bearing process environment or a JDBC exception's connection details.
            log.error("Mobile catalog publication failed: {}", failure.getClass().getSimpleName());
        }
    }
}
