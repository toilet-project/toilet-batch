package com.example.toiletbatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
public class ToiletSyncWriterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PublicDataChangeReviewWriter reviewWriter;

    @Test
    void updatesExistingRowsInsertsNewRowsAndSkipsRowsWithoutManagementNumber() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0, 1, 1, 0, 0, 1, 1);

        when(reviewWriter.capture(anyString(), any())).thenReturn(PublicDataChangeReviewWriter.Capture.NOT_PROTECTED);
        ToiletSyncWriter writer = new ToiletSyncWriter(jdbcTemplate, reviewWriter);
        RestroomSyncWriteResult result = writer.upsertPage("execution-1", List.of(record("EXISTING"), record("NEW"), record("")));

        assertEquals(1, result.updatedRecords());
        assertEquals(1, result.insertedRecords());
        assertEquals(1, result.skippedRecords());
        verify(jdbcTemplate, times(7)).update(anyString(), any(Object[].class));
    }

    private ResolvedRestroomRecord record(String managementNumber) {
        PublicRestroomRecord restroom = new PublicRestroomRecord(
                managementNumber, "테스트", "개방", "공중", "도로명", "지번", null, null,
                1, 2, 0, 0, 0, 0, 3, 0, 0,
                "기관", "042-000-0000", "상시", "", "202601", "Y", "입구", "Y", "Y", "벽면", "20260101", "20260101000000"
        );
        return new ResolvedRestroomRecord(
                restroom, new BigDecimal("36.3500000"), new BigDecimal("127.3800000"),
                "GEOCODED_ROAD", "a".repeat(64), LocalDateTime.of(2026, 8, 26, 2, 0)
        );
    }

    @Test
    void preservesCoordinatesCommittedAfterGeocodingSnapshotWasRead() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:writer-region-guard;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(ds);
        db.execute("CREATE ALIAS SHA2 FOR 'com.example.toiletbatch.batch.ToiletSyncWriterTest.sha2'");
        db.execute("""
                CREATE TABLE toilet(toilet_id BIGINT AUTO_INCREMENT PRIMARY KEY,mng_no VARCHAR(50), name VARCHAR(100), toilet_type VARCHAR(20),
                road_address VARCHAR(255), jibun_address VARCHAR(255), latitude DECIMAL(10,7), longitude DECIMAL(10,7),
                male_toilet_count INT, male_urinal_count INT, male_disabled_toilet_count INT, male_disabled_urinal_count INT,
                male_child_toilet_count INT, male_child_urinal_count INT, female_toilet_count INT,
                female_disabled_toilet_count INT, female_child_toilet_count INT, agency_name VARCHAR(100), phone_number VARCHAR(20),
                open_time VARCHAR(50), open_time_detail VARCHAR(255), installation_date VARCHAR(20), ownership_type VARCHAR(50),
                has_emergency_bell VARCHAR(10), emergency_bell_location VARCHAR(100), has_cctv VARCHAR(10),
                has_diaper_table VARCHAR(10), diaper_table_location VARCHAR(100), data_base_date VARCHAR(20),
                coordinate_source VARCHAR(30), geocoded_address_hash CHAR(64), geocoded_at TIMESTAMP,
                data_source VARCHAR(20), region_revision BIGINT NOT NULL DEFAULT 1,
                visibility_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE')
                """);
        db.execute("""
                CREATE TABLE toilet_translation(
                    toilet_id BIGINT NOT NULL,locale VARCHAR(12) NOT NULL,name VARCHAR(255),
                    road_address VARCHAR(500),jibun_address VARCHAR(500),source_hash CHAR(64) NOT NULL,
                    translation_status VARCHAR(24) NOT NULL,translation_source VARCHAR(40) NOT NULL,
                    manual_override BOOLEAN NOT NULL DEFAULT FALSE,version BIGINT NOT NULL DEFAULT 1,
                    translated_at TIMESTAMP,reviewed_at TIMESTAMP,created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,PRIMARY KEY(toilet_id,locale))
                """);
        db.update("INSERT INTO toilet(mng_no,latitude,longitude,coordinate_source) VALUES('A',37.5,127.5,'ADMIN_CONFIRMED'),('B',NULL,NULL,'LEGACY')");
        db.update("UPDATE toilet SET road_address=NULL,jibun_address='관리자 확정 지번' WHERE mng_no='A'");
        var capture = mock(PublicDataChangeReviewWriter.class);
        when(capture.capture(anyString(), any())).thenReturn(PublicDataChangeReviewWriter.Capture.NOT_PROTECTED);
        var writer = new ToiletSyncWriter(db, capture);
        writer.upsertPage("execution-2", List.of(record("A"), record("B")));
        assertEquals(new BigDecimal("37.5000000"), db.queryForObject("SELECT latitude FROM toilet WHERE mng_no='A'", BigDecimal.class));
        assertEquals("ADMIN_CONFIRMED", db.queryForObject("SELECT coordinate_source FROM toilet WHERE mng_no='A'", String.class));
        org.junit.jupiter.api.Assertions.assertNull(db.queryForObject("SELECT road_address FROM toilet WHERE mng_no='A'", String.class));
        assertEquals("관리자 확정 지번", db.queryForObject("SELECT jibun_address FROM toilet WHERE mng_no='A'", String.class));
        assertEquals("도로명", db.queryForObject("SELECT road_address FROM toilet WHERE mng_no='B'", String.class));
        assertEquals("지번", db.queryForObject("SELECT jibun_address FROM toilet WHERE mng_no='B'", String.class));
        assertEquals(new BigDecimal("36.3500000"), db.queryForObject("SELECT latitude FROM toilet WHERE mng_no='B'", BigDecimal.class));
        assertEquals(1L, db.queryForObject("SELECT region_revision FROM toilet WHERE mng_no='A'", Long.class));
        assertEquals(3L, db.queryForObject("SELECT region_revision FROM toilet WHERE mng_no='B'", Long.class));
        db.update("INSERT INTO toilet(mng_no,name,road_address,visibility_status,coordinate_source) VALUES('H','숨김 원본','숨김 주소','HIDDEN_DUPLICATE','LEGACY')");
        writer.upsertPage("execution-hidden", List.of(record("H")));
        assertEquals("숨김 원본", db.queryForObject("SELECT name FROM toilet WHERE mng_no='H'", String.class));
        assertEquals("숨김 주소", db.queryForObject("SELECT road_address FROM toilet WHERE mng_no='H'", String.class));
        org.junit.jupiter.api.Assertions.assertNull(db.queryForObject("SELECT latitude FROM toilet WHERE mng_no='H'", BigDecimal.class));
        assertEquals("HIDDEN_DUPLICATE", db.queryForObject("SELECT visibility_status FROM toilet WHERE mng_no='H'", String.class));
        assertEquals(1L, db.queryForObject("SELECT region_revision FROM toilet WHERE mng_no='H'", Long.class));
        assertEquals(3L, db.queryForObject("SELECT COUNT(*) FROM toilet_translation WHERE locale='ko'", Long.class));
        assertEquals("관리자 확정 지번", db.queryForObject("""
                SELECT tr.jibun_address FROM toilet_translation tr
                JOIN toilet t ON t.toilet_id=tr.toilet_id WHERE t.mng_no='A' AND tr.locale='ko'
                """, String.class));
    }

    public static String sha2(String input, int bits) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
