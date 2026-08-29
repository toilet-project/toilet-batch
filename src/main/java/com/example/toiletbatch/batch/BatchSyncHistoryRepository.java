package com.example.toiletbatch.batch;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BatchSyncHistoryRepository {

    private static final String JOB_NAME = "PUBLIC_RESTROOM_SYNC";

    private static final String INSERT_SUCCESS_SQL = """
            INSERT INTO batch_sync_history (
                job_name, trigger_type, status, range_from, range_to,
                requested_pages, received_records, inserted_records, updated_records,
                skipped_records, failed_records, total_toilet_count, started_at, completed_at
            ) VALUES (?, ?, 'SUCCESS', ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """;

    private static final String INSERT_FAILURE_SQL = """
            INSERT INTO batch_sync_history (
                job_name, trigger_type, status, range_from, range_to,
                requested_pages, received_records, inserted_records, updated_records,
                skipped_records, failed_records, total_toilet_count, started_at, completed_at, error_message
            ) VALUES (?, ?, 'FAILED', ?, ?, 0, 0, 0, 0, 0, 0, NULL, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public BatchSyncHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recordSuccess(
            BatchSyncTrigger trigger,
            RestroomSyncResult result,
            long totalToiletCount,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
        jdbcTemplate.update(
                INSERT_SUCCESS_SQL,
                JOB_NAME,
                trigger.name(),
                result.fromInclusive(),
                result.toExclusive(),
                result.requestedPages(),
                result.receivedRecords(),
                result.insertedRecords(),
                result.updatedRecords(),
                result.skippedRecords(),
                totalToiletCount,
                startedAt,
                completedAt
        );
    }

    public void recordFailure(
            BatchSyncTrigger trigger,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            RuntimeException exception
    ) {
        jdbcTemplate.update(
                INSERT_FAILURE_SQL,
                JOB_NAME,
                trigger.name(),
                fromInclusive,
                toExclusive,
                startedAt,
                completedAt,
                abbreviate(exception.getMessage())
        );
    }

    public long countToilets() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM toilet", Long.class);
        return count == null ? 0L : count;
    }

    private String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "알 수 없는 배치 실행 오류";
        }
        return message.length() <= 1_000 ? message : message.substring(0, 1_000);
    }
}
