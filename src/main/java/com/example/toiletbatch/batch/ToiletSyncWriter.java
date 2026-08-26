package com.example.toiletbatch.batch;

import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class ToiletSyncWriter {

    private static final String UPDATE_SQL = """
            UPDATE toilet
               SET name = ?, toilet_type = ?, road_address = ?, jibun_address = ?,
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
                data_base_date, data_source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLIC_DATA')
            """;

    private final JdbcTemplate jdbcTemplate;

    public ToiletSyncWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public RestroomSyncWriteResult upsertPage(List<PublicRestroomRecord> records) {
        int inserted = 0;
        int updated = 0;
        int skipped = 0;

        for (PublicRestroomRecord record : records) {
            if (!StringUtils.hasText(record.managementNumber())) {
                skipped++;
                continue;
            }

            int affectedRows = jdbcTemplate.update(UPDATE_SQL, updateArguments(record));
            if (affectedRows > 0) {
                updated++;
                continue;
            }

            jdbcTemplate.update(INSERT_SQL, insertArguments(record));
            inserted++;
        }
        return new RestroomSyncWriteResult(inserted, updated, skipped);
    }

    private Object[] updateArguments(PublicRestroomRecord record) {
        return new Object[]{
                record.name(), record.toiletType(), record.roadAddress(), record.jibunAddress(),
                record.maleToiletCount(), record.maleUrinalCount(),
                record.maleDisabledToiletCount(), record.maleDisabledUrinalCount(),
                record.maleChildToiletCount(), record.maleChildUrinalCount(),
                record.femaleToiletCount(), record.femaleDisabledToiletCount(), record.femaleChildToiletCount(),
                record.agencyName(), record.phoneNumber(), record.openTime(), record.openTimeDetail(),
                record.installationDate(), record.ownershipType(), record.hasEmergencyBell(),
                record.emergencyBellLocation(), record.hasCctv(), record.hasDiaperTable(),
                record.diaperTableLocation(), record.dataBaseDate(), record.managementNumber()
        };
    }

    private Object[] insertArguments(PublicRestroomRecord record) {
        return new Object[]{
                record.managementNumber(), record.name(), record.toiletType(), record.roadAddress(),
                record.jibunAddress(), record.latitude(), record.longitude(), record.maleToiletCount(),
                record.maleUrinalCount(), record.maleDisabledToiletCount(), record.maleDisabledUrinalCount(),
                record.maleChildToiletCount(), record.maleChildUrinalCount(), record.femaleToiletCount(),
                record.femaleDisabledToiletCount(), record.femaleChildToiletCount(), record.agencyName(),
                record.phoneNumber(), record.openTime(), record.openTimeDetail(), record.installationDate(),
                record.ownershipType(), record.hasEmergencyBell(), record.emergencyBellLocation(),
                record.hasCctv(), record.hasDiaperTable(), record.diaperTableLocation(), record.dataBaseDate()
        };
    }
}
