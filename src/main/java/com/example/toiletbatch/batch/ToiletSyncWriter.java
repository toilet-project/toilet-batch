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
               SET name = ?, toilet_type = ?,
                   road_address = CASE WHEN coordinate_source = 'ADMIN_CONFIRMED' THEN road_address ELSE ? END,
                   jibun_address = CASE WHEN coordinate_source = 'ADMIN_CONFIRMED' THEN jibun_address ELSE ? END,
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

    private static final String FILL_MISSING_COORDINATE_SQL = """
            UPDATE toilet SET latitude = ?, longitude = ?, coordinate_source = ?,
                geocoded_address_hash = ?, geocoded_at = ?
            WHERE mng_no = ? AND latitude IS NULL AND longitude IS NULL
                AND coordinate_source <> 'ADMIN_CONFIRMED'
            """;

    public ToiletSyncWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public RestroomSyncWriteResult upsertPage(List<ResolvedRestroomRecord> records) {
        int inserted = 0;
        int updated = 0;
        int skipped = 0;

        for (ResolvedRestroomRecord resolvedRecord : records) {
            var record = resolvedRecord.restroom();
            if (!StringUtils.hasText(record.managementNumber())) {
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
                record.name(), record.toiletType(), record.roadAddress(), record.jibunAddress(),
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
