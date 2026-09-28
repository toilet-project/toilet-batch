package com.example.toiletbatch.mobile;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.mobile-catalog")
public record MobileCatalogProperties(boolean enabled, String origin, String publishToken,
                                      String python, String script, String workDirectory, int timeoutSeconds) {
    public MobileCatalogProperties {
        origin = origin == null || origin.isBlank() ? "https://mobile-data.geupddong.com" : origin;
        publishToken = publishToken == null ? "" : publishToken;
        python = python == null || python.isBlank() ? "python3" : python;
        script = script == null || script.isBlank() ? "/app/mobile-data/publish.py" : script;
        workDirectory = workDirectory == null || workDirectory.isBlank() ? "/tmp/geupddong-mobile-catalog" : workDirectory;
        timeoutSeconds = timeoutSeconds <= 0 ? 1200 : timeoutSeconds;
    }
    @Override public String toString() {
        return "MobileCatalogProperties[enabled=" + enabled + ", origin=" + origin + ", publishToken=REDACTED]";
    }
}
