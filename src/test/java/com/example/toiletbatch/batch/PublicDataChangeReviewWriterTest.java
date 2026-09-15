package com.example.toiletbatch.batch;

import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PublicDataChangeReviewWriterTest {
    private JdbcTemplate db;
    private PublicDataChangeReviewWriter writer;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:public-data-review-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(dataSource);
        db.execute("""
                CREATE TABLE toilet(
                  toilet_id BIGINT AUTO_INCREMENT PRIMARY KEY,mng_no VARCHAR(50),coordinate_source VARCHAR(30),
                  latitude DECIMAL(10,7),longitude DECIMAL(10,7),road_address VARCHAR(255),jibun_address VARCHAR(255))
                """);
        db.execute("""
                CREATE TABLE public_data_change_review(
                  review_id BIGINT AUTO_INCREMENT PRIMARY KEY,toilet_id BIGINT NOT NULL,active_toilet_id BIGINT NULL UNIQUE,
                  baseline_latitude DECIMAL(10,7),baseline_longitude DECIMAL(10,7),baseline_road_address VARCHAR(255),baseline_jibun_address VARCHAR(255),
                  proposal_latitude DECIMAL(10,7),proposal_longitude DECIMAL(10,7),proposal_road_address VARCHAR(255),proposal_jibun_address VARCHAR(255),
                  changed_fields VARCHAR(100),baseline_hash CHAR(64),proposal_hash CHAR(64),provider_updated_at DATETIME,
                  first_received_at DATETIME,last_received_at DATETIME,receipt_count INT,status VARCHAR(20),status_reason VARCHAR(500),
                  version BIGINT,superseded_by_review_id BIGINT,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("""
                CREATE TABLE public_data_confirmed_receipt(
                  receipt_id BIGINT AUTO_INCREMENT PRIMARY KEY,execution_key CHAR(36),toilet_id BIGINT,review_id BIGINT,
                  received_at DATETIME,input_hash CHAR(64),protected_before_hash CHAR(64),protected_after_hash CHAR(64),result VARCHAR(30),
                  UNIQUE(execution_key,toilet_id))
                """);
        db.update("""
                INSERT INTO toilet(mng_no,coordinate_source,latitude,longitude,road_address,jibun_address)
                VALUES ('ADMIN-1','ADMIN_CONFIRMED',37.5000000,127.1000000,'서울 도로 1','서울 지번 1')
                """);
        writer = new PublicDataChangeReviewWriter(db);
    }

    @Test
    void repeatsOneCandidateAndReusesKeepCurrentDecision() {
        assertEquals(PublicDataChangeReviewWriter.Capture.RECORDED,
                writer.capture(key(1), resolved("서울 도로 2", "서울 지번 2", "37.6000000", "127.2000000")));
        assertEquals(PublicDataChangeReviewWriter.Capture.RECORDED,
                writer.capture(key(2), resolved("서울 도로 2", "서울 지번 2", "37.6000000", "127.2000000")));

        assertEquals(1L, count("public_data_change_review"));
        assertEquals(2, db.queryForObject("SELECT receipt_count FROM public_data_change_review", Integer.class));
        assertEquals(2L, count("public_data_confirmed_receipt"));

        db.update("UPDATE public_data_change_review SET status='KEPT_CURRENT',active_toilet_id=NULL WHERE review_id=1");
        writer.capture(key(3), resolved("서울 도로 2", "서울 지번 2", "37.6000000", "127.2000000"));

        assertEquals(1L, count("public_data_change_review"));
        assertEquals("KEPT_CURRENT", db.queryForObject(
                "SELECT result FROM public_data_confirmed_receipt WHERE execution_key=?", String.class, key(3)));
    }

    @Test
    void supersedesOldProposalAndClosesQueueWhenProviderMatchesCurrent() {
        writer.capture(key(1), resolved("서울 도로 2", "서울 지번 2", "37.6000000", "127.2000000"));
        writer.capture(key(2), resolved("서울 도로 3", "서울 지번 3", "37.7000000", "127.3000000"));

        assertEquals(2L, count("public_data_change_review"));
        assertEquals("SUPERSEDED", db.queryForObject(
                "SELECT status FROM public_data_change_review WHERE review_id=1", String.class));
        assertEquals(2L, db.queryForObject(
                "SELECT superseded_by_review_id FROM public_data_change_review WHERE review_id=1", Long.class));
        assertNotNull(db.queryForObject(
                "SELECT active_toilet_id FROM public_data_change_review WHERE review_id=2", Long.class));

        writer.capture(key(3), resolved("서울 도로 1", "서울 지번 1", "37.5000000", "127.1000000"));
        assertEquals("SUPERSEDED", db.queryForObject(
                "SELECT status FROM public_data_change_review WHERE review_id=2", String.class));
        assertEquals("MATCHED_CURRENT", db.queryForObject(
                "SELECT result FROM public_data_confirmed_receipt WHERE execution_key=?", String.class, key(3)));
    }

    @Test
    void marksConflictingInputsInOneExecutionAndMakesCandidateUnapprovable() {
        writer.capture(key(1), resolved("서울 도로 2", "서울 지번 2", "37.6000000", "127.2000000"));
        assertEquals(PublicDataChangeReviewWriter.Capture.CONFLICT,
                writer.capture(key(1), resolved("서울 도로 3", "서울 지번 3", "37.7000000", "127.3000000")));

        assertEquals("CONFLICT", db.queryForObject("SELECT result FROM public_data_confirmed_receipt", String.class));
        assertEquals("SUPERSEDED", db.queryForObject("SELECT status FROM public_data_change_review", String.class));
        assertEquals("BATCH_INPUT_CONFLICT", db.queryForObject("SELECT status_reason FROM public_data_change_review", String.class));
    }

    @Test
    void exactRetryOfOneExecutionDoesNotIncreaseEvidenceTwice() {
        var record = resolved("서울 도로 2", "서울 지번 2", "37.6000000", "127.2000000");
        writer.capture(key(1), record);
        assertEquals(PublicDataChangeReviewWriter.Capture.DUPLICATE, writer.capture(key(1), record));
        assertEquals(1L, count("public_data_confirmed_receipt"));
        assertEquals(1, db.queryForObject("SELECT receipt_count FROM public_data_change_review", Integer.class));
    }

    @Test
    void comparisonHashHasStableCrossApplicationContract() {
        assertEquals("482cf0a2de03f7b8290bbd66278a00c8deab5acfbdfacb1ee29f0949f7288fc4",
                PublicDataChangeReviewWriter.hash(new BigDecimal("37.5"), new BigDecimal("127.1"),
                        " 서울  도로 1 ", "서울 지번 1"));
    }

    private ResolvedRestroomRecord resolved(String road, String jibun, String latitude, String longitude) {
        var restroom = new PublicRestroomRecord("ADMIN-1", "테스트", "개방", "공중", road, jibun,
                new BigDecimal(latitude), new BigDecimal(longitude), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "기관", "전화", "상시", "", "", "", "", "", "", "", "20260915020000", "20260915020000");
        return new ResolvedRestroomRecord(restroom, new BigDecimal(latitude), new BigDecimal(longitude),
                "ADMIN_CONFIRMED", "a".repeat(64), LocalDateTime.of(2026, 9, 15, 2, 0));
    }

    private long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private String key(int value) { return "00000000-0000-0000-0000-" + String.format("%012d", value); }
}
