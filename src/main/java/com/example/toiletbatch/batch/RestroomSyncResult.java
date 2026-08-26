package com.example.toiletbatch.batch;

import java.time.LocalDateTime;

public record RestroomSyncResult(
        LocalDateTime fromInclusive,
        LocalDateTime toExclusive,
        int requestedPages,
        int receivedRecords,
        int insertedRecords,
        int updatedRecords,
        int skippedRecords
) {
}
