package com.example.toiletbatch.batch;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.restroom-sync")
public record RestroomSyncProperties(
        String cron,
        String zone,
        int overlapDays,
        int maxAttempts
) {

    public RestroomSyncProperties {
        cron = (cron == null || cron.isBlank()) ? "0 0 2 * * *" : cron;
        zone = (zone == null || zone.isBlank()) ? "Asia/Seoul" : zone;
        overlapDays = overlapDays < 1 ? 3 : overlapDays;
        maxAttempts = maxAttempts < 1 ? 3 : maxAttempts;
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
