package com.example.toiletbatch.region;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;

class RegionRepositoryTest {
    RegionRepository repository;
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        repository = new RegionRepository(ds); jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY, road_address VARCHAR(255), jibun_address VARCHAR(255), latitude DECIMAL(10,7), longitude DECIMAL(10,7), coordinate_source VARCHAR(30), geocoded_at TIMESTAMP)");
        jdbc.execute("""
                CREATE TABLE toilet_region_assessment_history (
                assessment_id BIGINT AUTO_INCREMENT PRIMARY KEY, toilet_id BIGINT, source_hash CHAR(64),
                algorithm_version VARCHAR(40), status VARCHAR(30), reason VARCHAR(100), result_json TEXT,
                checked_epoch_millis BIGINT, checked_at TIMESTAMP,
                UNIQUE(toilet_id,source_hash,algorithm_version,checked_epoch_millis))
                """);
        jdbc.execute("""
                CREATE TABLE toilet_region(toilet_id BIGINT PRIMARY KEY, sido_name VARCHAR(50), sido_code CHAR(2),
                sigungu_name VARCHAR(100), sigungu_code CHAR(5), city_name VARCHAR(50), district_name VARCHAR(50),
                legal_dong_code CHAR(10), administrative_dong_code CHAR(10), region_source VARCHAR(40), status VARCHAR(30),
                reason VARCHAR(100), source_hash CHAR(64), source_latitude DECIMAL(10,7), source_longitude DECIMAL(10,7),
                source_road_address VARCHAR(255), source_jibun_address VARCHAR(255), evaluated_latitude DECIMAL(10,7),
                evaluated_longitude DECIMAL(10,7), result_json TEXT, checked_at TIMESTAMP)
                """);
    }
    @Test void existingCoordinatesAndAddressesArePreservedAndReplayIsIdempotent() throws Exception {
        jdbc.update("INSERT INTO toilet VALUES(1,'대전 유성구 대학로','대전 유성구 궁동',36.3,127.3,'ADMIN_CONFIRMED',NULL)");
        Source source = repository.page(0, 100).getFirst();
        Result r = result(source, source.point(), Status.VERIFIED, "NONE");
        assertFalse(repository.apply(r, true).coordinatesFilled());
        repository.apply(r, true);
        assertEquals(source, repository.page(0, 100).getFirst());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM toilet_region", Integer.class));
        assertEquals(r, repository.stored(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM toilet_region_assessment_history", Integer.class));
    }
    @Test void concurrentLocationOrAddressChangeDiscardsStaleResult() throws Exception {
        jdbc.update("INSERT INTO toilet VALUES(1,'대전 유성구',NULL,36.3,127.3,'LEGACY',NULL)");
        Source snapshot = repository.page(0, 100).getFirst();
        jdbc.update("UPDATE toilet SET latitude=37.3, road_address='서울 강남구' WHERE toilet_id=1");
        assertTrue(repository.apply(result(snapshot, snapshot.point(), Status.VERIFIED, "NONE"), false).conflict());
        assertNull(repository.stored(1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM toilet_region_assessment_history", Integer.class));
        assertEquals(new BigDecimal("37.3000000"), repository.page(0, 100).getFirst().latitude());
    }
    @Test void fillRequiresExplicitOptionVerifiedResultAndBothCoordinatesEmpty() throws Exception {
        jdbc.update("INSERT INTO toilet VALUES(1,'대전 유성구',NULL,NULL,NULL,'LEGACY',NULL)");
        Source source = repository.page(0, 100).getFirst();
        Point candidate = new Point(new BigDecimal("36.3000000"), new BigDecimal("127.3000000"));
        assertFalse(repository.apply(result(source, candidate, Status.MISMATCH, "ROAD"), true).coordinatesFilled());
        assertFalse(repository.apply(result(source, candidate, Status.VERIFIED, "ROAD"), false).coordinatesFilled());
        assertNull(repository.page(0, 100).getFirst().latitude());
        assertTrue(repository.apply(result(source, candidate, Status.VERIFIED, "ROAD"), true).coordinatesFilled());
        assertEquals(candidate, repository.page(0, 100).getFirst().point());
        assertEquals("대전 유성구", repository.page(0, 100).getFirst().roadAddress());
    }
    @Test void invalidationUsesActualCoordinatesNotUpdatedAt() throws Exception {
        jdbc.update("INSERT INTO toilet VALUES(1,'대전 유성구',NULL,36.3,127.3,'LEGACY',NULL)");
        Source source = repository.page(0, 100).getFirst();
        Result r = result(source, source.point(), Status.VERIFIED, "NONE");
        repository.apply(r, false);
        jdbc.update("UPDATE toilet SET coordinate_source='ADMIN_CONFIRMED' WHERE toilet_id=1");
        assertTrue(RegionJob.fresh(repository.stored(1), repository.page(0, 100).getFirst(), r.checkedEpochMillis()));
        jdbc.update("UPDATE toilet SET longitude=127.4 WHERE toilet_id=1");
        assertFalse(RegionJob.fresh(repository.stored(1), repository.page(0, 100).getFirst(), r.checkedEpochMillis()));
    }
    @Test void recheckPreservesPriorDecisionEvidenceAndHistoryFailureRollsBack() throws Exception {
        jdbc.update("INSERT INTO toilet VALUES(1,'unknown',NULL,36.3,127.3,'LEGACY',NULL)");
        Source source = repository.page(0, 100).getFirst();
        Result first = result(source, source.point(), Status.ADDRESS_UNVERIFIED, "NONE");
        repository.apply(first, false);
        var evidence = new Evidence(new ReverseAddress("대전", "유성구", null, "대전 유성구"), 50,
                java.util.List.of(), java.util.List.of("NO_ADDRESS_RESULT"));
        Result next = new Result(source, source.point(), first.region(), Status.ADDRESS_UNVERIFIED, "RECHECK_MANUAL_REVIEW",
                Check.UNKNOWN, Check.UNKNOWN, "NONE", first.checkedEpochMillis() + 1000, VERSION, evidence);
        repository.apply(next, false); repository.apply(next, false);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM toilet_region_assessment_history", Integer.class));
        assertEquals(next, repository.stored(1));
        assertEquals(first.reason(), jdbc.queryForObject("SELECT reason FROM toilet_region_assessment_history ORDER BY assessment_id LIMIT 1", String.class));
        jdbc.execute("DROP TABLE toilet_region_assessment_history");
        assertThrows(Exception.class, () -> repository.apply(first, false));
        assertEquals(next, repository.stored(1)); assertEquals(source, repository.page(0, 1).getFirst());
    }
    private Result result(Source source, Point point, Status status, String fallback) {
        Region region = new Region("대전광역시", "30", "유성구", "30200", null, null, "3020012200", null);
        return new Result(source, point, region, status, "TEST", Check.MATCH, Check.UNKNOWN, fallback, Clock.systemUTC().millis(), VERSION);
    }
}
