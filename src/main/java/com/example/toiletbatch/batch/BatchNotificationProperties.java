package com.example.toiletbatch.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.notification")
public record BatchNotificationProperties(String webhookUrl) {

    public boolean enabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
