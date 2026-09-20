package com.example.toiletbatch.openinghours;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OpeningHoursSynchronizerTest {
    JdbcTemplate jdbc;
    OpeningHoursSynchronizer synchronizer;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:opening-hours;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS toilet_opening_schedule");
        jdbc.execute("DROP TABLE IF EXISTS toilet_opening_hours");
        jdbc.execute("DROP TABLE IF EXISTS toilet");
        jdbc.execute("""
                CREATE TABLE toilet(
                    toilet_id BIGINT AUTO_INCREMENT PRIMARY KEY,mng_no VARCHAR(50) UNIQUE,
                    open_time VARCHAR(50),open_time_detail VARCHAR(255))
                """);
        jdbc.execute("""
                CREATE TABLE toilet_opening_hours(
                    toilet_id BIGINT PRIMARY KEY,source_hash CHAR(64) NOT NULL,opening_policy VARCHAR(24) NOT NULL,
                    is_open_24h BOOLEAN,normalization_status VARCHAR(24) NOT NULL,confidence DECIMAL(5,4),
                    parser_version VARCHAR(20) NOT NULL,holiday_policy VARCHAR(16) NOT NULL,
                    manual_override BOOLEAN NOT NULL DEFAULT FALSE,source_changed BOOLEAN NOT NULL DEFAULT FALSE,
                    confirmed_by_user_id BIGINT,confirmed_at TIMESTAMP,created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE toilet_opening_schedule(
                    schedule_id BIGINT AUTO_INCREMENT PRIMARY KEY,toilet_id BIGINT NOT NULL,
                    day_of_week TINYINT NOT NULL,slot_index TINYINT NOT NULL,start_time TIME,end_time TIME,
                    crosses_midnight BOOLEAN NOT NULL,is_closed BOOLEAN NOT NULL,
                    UNIQUE(toilet_id,day_of_week,slot_index))
                """);
        jdbc.update("""
                INSERT INTO toilet(mng_no,open_time,open_time_detail) VALUES
                    ('A','상시','연중무휴 09:00~18:00'),('B','정시','24시간')
                """);
        synchronizer = new OpeningHoursSynchronizer(jdbc, new OpeningHoursParser());
    }

    @Test
    void storesWeeklyScheduleAndExplicitTwentyFourHours() {
        synchronizer.synchronize("A");
        synchronizer.synchronize("B");

        assertFalse(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours oh JOIN toilet t ON t.toilet_id=oh.toilet_id WHERE t.mng_no='A'",
                Boolean.class));
        assertEquals(7, jdbc.queryForObject(
                "SELECT COUNT(*) FROM toilet_opening_schedule s JOIN toilet t ON t.toilet_id=s.toilet_id WHERE t.mng_no='A'",
                Integer.class));
        assertTrue(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours oh JOIN toilet t ON t.toilet_id=oh.toilet_id WHERE t.mng_no='B'",
                Boolean.class));
    }

    @Test
    void manualConfirmationIsKeptWhenRawSourceChanges() {
        synchronizer.synchronize("B");
        jdbc.update("""
                UPDATE toilet_opening_hours oh
                SET manual_override=TRUE,normalization_status='CONFIRMED'
                WHERE toilet_id=(SELECT toilet_id FROM toilet WHERE mng_no='B')
                """);
        jdbc.update("UPDATE toilet SET open_time='정시',open_time_detail='09:00~18:00' WHERE mng_no='B'");

        synchronizer.synchronize("B");

        assertTrue(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours oh JOIN toilet t ON t.toilet_id=oh.toilet_id WHERE t.mng_no='B'",
                Boolean.class));
        assertTrue(jdbc.queryForObject(
                "SELECT source_changed FROM toilet_opening_hours oh JOIN toilet t ON t.toilet_id=oh.toilet_id WHERE t.mng_no='B'",
                Boolean.class));
    }

    @Test
    void unchangedSourceAtCurrentParserVersionDoesNotTouchNormalizedRow() {
        synchronizer.synchronize("B");
        jdbc.update("UPDATE toilet_opening_hours SET updated_at=TIMESTAMP '2026-01-01 00:00:00'");

        synchronizer.synchronize("B");

        assertEquals("2026-01-01 00:00:00", jdbc.queryForObject(
                "SELECT FORMATDATETIME(updated_at,'yyyy-MM-dd HH:mm:ss') FROM toilet_opening_hours", String.class));
    }

    @Test
    void sameSourceIsReparsedWhenParserVersionIsOld() {
        synchronizer.synchronize("B");
        jdbc.update("UPDATE toilet_opening_hours SET parser_version='bootstrap-v1',is_open_24h=FALSE");

        synchronizer.synchronize("B");

        assertEquals(OpeningHoursParser.VERSION, jdbc.queryForObject(
                "SELECT parser_version FROM toilet_opening_hours", String.class));
        assertTrue(jdbc.queryForObject("SELECT is_open_24h FROM toilet_opening_hours", Boolean.class));
    }
}
