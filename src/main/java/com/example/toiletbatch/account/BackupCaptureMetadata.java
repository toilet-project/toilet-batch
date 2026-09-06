package com.example.toiletbatch.account;

import java.time.Instant;
import java.util.UUID;

/** Bound to encrypted dump bytes. Local JSON is diagnostic metadata, NOT authenticated provenance. */
public record BackupCaptureMetadata(int version, String filename, String sha256, long bytes,
                                   String captureStartedAt, String captureCompletedAt,
                                   String database, String serverUuid, String databaseEpoch) {
    public BackupCaptureMetadata {
        try {
            if (version != 1 || !filename.matches("toilet-db-[0-9]{8}-[0-9]{6}\\.sql\\.gz\\.enc")
                    || !sha256.matches("[a-f0-9]{64}") || bytes < 1 || !"toilet_db".equals(database)
                    || !UUID.fromString(serverUuid).toString().equals(serverUuid)
                    || !UUID.fromString(databaseEpoch).toString().equals(databaseEpoch)
                    || Instant.parse(captureStartedAt).isAfter(Instant.parse(captureCompletedAt))) throw new IllegalArgumentException();
        } catch (RuntimeException ignored) { throw new IllegalArgumentException("INVALID_BACKUP_CAPTURE_METADATA"); }
    }
}
