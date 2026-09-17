package com.example.toiletbatch.batch;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class ToiletSyncWriter {

    private static final String UPDATE_SQL = """
            UPDATE toilet
               SET name = CASE WHEN visibility_status='HIDDEN_DUPLICATE' THEN name ELSE ? END, toilet_type = ?,
                   region_revision = region_revision + CASE
                       WHEN coordinate_source = 'ADMIN_CONFIRMED' OR visibility_status='HIDDEN_DUPLICATE' THEN 0
                       WHEN (road_address = ? OR (road_address IS NULL AND ? IS NULL))
                        AND (jibun_address = ? OR (jibun_address IS NULL AND ? IS NULL)) THEN 0
                       ELSE 1 END,
                   road_address = CASE WHEN coordinate_source = 'ADMIN_CONFIRMED' OR visibility_status='HIDDEN_DUPLICATE' THEN road_address ELSE ? END,
                   jibun_address = CASE WHEN coordinate_source = 'ADMIN_CONFIRMED' OR visibility_status='HIDDEN_DUPLICATE' THEN jibun_address ELSE ? END,
                   male_toilet_count = ?, male_urinal_count = ?,
                   male_disabled_toilet_count = ?, male_disabled_urinal_count = ?,
                   male_child_toilet_count = ?, male_child_urinal_count = ?,
                   female_toilet_count = ?, female_disabled_toilet_count = ?,
                   female_child_toilet_count = ?, agency_name = ?, phone_number = ?,
                   open_time = ?, open_time_detail = ?, installation_date = ?, ownership_type = ?,
                   has_emergency_bell = ?, emergency_bell_location = ?, has_cctv = ?,
                   has_diaper_table = ?, diaper_table_location = ?, data_base_date = ?,
                   data_source = 'PUBLIC_DATA'
             WHERE mng_no = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO toilet (
                mng_no, name, toilet_type, road_address, jibun_address, latitude, longitude,
                male_toilet_count, male_urinal_count, male_disabled_toilet_count, male_disabled_urinal_count,
                male_child_toilet_count, male_child_urinal_count, female_toilet_count,
                female_disabled_toilet_count, female_child_toilet_count, agency_name, phone_number,
                open_time, open_time_detail, installation_date, ownership_type, has_emergency_bell,
                emergency_bell_location, has_cctv, has_diaper_table, diaper_table_location,
                data_base_date, coordinate_source, geocoded_address_hash, geocoded_at, data_source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLIC_DATA')
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PublicDataChangeReviewWriter reviewWriter;

    private static final String FILL_MISSING_COORDINATE_SQL = """
            UPDATE toilet SET latitude = ?, longitude = ?, coordinate_source = ?,
                geocoded_address_hash = ?, geocoded_at = ?, region_revision = region_revision + 1
            WHERE mng_no = ? AND latitude IS NULL AND longitude IS NULL
                AND coordinate_source <> 'ADMIN_CONFIRMED'
                AND visibility_status='VISIBLE'
            """;

    public ToiletSyncWriter(JdbcTemplate jdbcTemplate, PublicDataChangeReviewWriter reviewWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.reviewWriter = reviewWriter;
    }

    @Transactional
    public RestroomSyncWriteResult upsertPage(String executionKey, List<ResolvedRestroomRecord> records) {
        if (!StringUtils.hasText(executionKey)) throw new IllegalArgumentException("배치 실행 식별자가 필요합니다.");
        int inserted = 0;
        int updated = 0;
        int skipped = 0;

        for (ResolvedRestroomRecord resolvedRecord : records) {
            var record = resolvedRecord.restroom();
            if (!StringUtils.hasText(record.managementNumber())) {
                skipped++;
                continue;
            }

            PublicDataChangeReviewWriter.Capture capture = reviewWriter.capture(executionKey, resolvedRecord);
            if (capture == PublicDataChangeReviewWriter.Capture.DUPLICATE
                    || capture == PublicDataChangeReviewWriter.Capture.CONFLICT) {
                skipped++;
                continue;
            }

            // A correction can commit after the metadata read. Never overwrite existing coordinates
            // with that stale snapshot; only genuinely empty pairs may be filled by the batch.
            jdbcTemplate.update(FILL_MISSING_COORDINATE_SQL, resolvedRecord.latitude(), resolvedRecord.longitude(),
                    resolvedRecord.coordinateSource(), resolvedRecord.geocodedAddressHash(),
                    resolvedRecord.geocodedAt(), record.managementNumber());
            int affectedRows = jdbcTemplate.update(UPDATE_SQL, updateArguments(resolvedRecord));
            if (affectedRows > 0) {
                updated++;
                continue;
            }

            jdbcTemplate.update(INSERT_SQL, insertArguments(resolvedRecord));
            inserted++;
        }
        return new RestroomSyncWriteResult(inserted, updated, skipped);
    }

    private Object[] updateArguments(ResolvedRestroomRecord resolvedRecord) {
        var record = resolvedRecord.restroom();
        return new Object[]{
                record.name(), record.toiletType(), record.roadAddress(), record.roadAddress(),
                record.jibunAddress(), record.jibunAddress(),
                record.roadAddress(), record.jibunAddress(),
                record.maleToiletCount(), record.maleUrinalCount(),
                record.maleDisabledToiletCount(), record.maleDisabledUrinalCount(),
                record.maleChildToiletCount(), record.maleChildUrinalCount(),
                record.femaleToiletCount(), record.femaleDisabledToiletCount(), record.femaleChildToiletCount(),
                record.agencyName(), record.phoneNumber(), record.openTime(), record.openTimeDetail(),
                record.installationDate(), record.ownershipType(), record.hasEmergencyBell(),
                record.emergencyBellLocation(), record.hasCctv(), record.hasDiaperTable(),
                record.diaperTableLocation(), record.dataBaseDate(),
                record.managementNumber()
        };
    }

    private Object[] insertArguments(ResolvedRestroomRecord resolvedRecord) {
        var record = resolvedRecord.restroom();
        return new Object[]{
                record.managementNumber(), record.name(), record.toiletType(), record.roadAddress(),
                record.jibunAddress(), resolvedRecord.latitude(), resolvedRecord.longitude(), record.maleToiletCount(),
                record.maleUrinalCount(), record.maleDisabledToiletCount(), record.maleDisabledUrinalCount(),
                record.maleChildToiletCount(), record.maleChildUrinalCount(), record.femaleToiletCount(),
                record.femaleDisabledToiletCount(), record.femaleChildToiletCount(), record.agencyName(),
                record.phoneNumber(), record.openTime(), record.openTimeDetail(), record.installationDate(),
                record.ownershipType(), record.hasEmergencyBell(), record.emergencyBellLocation(),
                record.hasCctv(), record.hasDiaperTable(), record.diaperTableLocation(), record.dataBaseDate(),
                resolvedRecord.coordinateSource(), resolvedRecord.geocodedAddressHash(), resolvedRecord.geocodedAt()
        };
    }
}
