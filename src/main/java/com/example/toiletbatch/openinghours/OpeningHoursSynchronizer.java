package com.example.toiletbatch.openinghours;

import static com.example.toiletbatch.openinghours.OpeningHoursModels.Slot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Time;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OpeningHoursSynchronizer {
    private static final String UPSERT_SQL = """
            INSERT INTO toilet_opening_hours
                (toilet_id,source_hash,opening_policy,is_open_24h,normalization_status,
                 confidence,parser_version,holiday_policy,manual_override,source_changed,
                 confirmed_by_user_id,confirmed_at,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,FALSE,FALSE,NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                source_hash=VALUES(source_hash),opening_policy=VALUES(opening_policy),
                is_open_24h=VALUES(is_open_24h),normalization_status=VALUES(normalization_status),
                confidence=VALUES(confidence),parser_version=VALUES(parser_version),
                holiday_policy=VALUES(holiday_policy),source_changed=FALSE,
                confirmed_by_user_id=NULL,confirmed_at=NULL,updated_at=VALUES(updated_at)
            """;

    private final JdbcTemplate jdbc;
    private final OpeningHoursParser parser;

    public OpeningHoursSynchronizer(JdbcTemplate jdbc, OpeningHoursParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    public void synchronize(String managementNumber) {
        List<Source> sources = jdbc.query("""
                SELECT toilet_id,open_time,open_time_detail FROM toilet WHERE mng_no=?
                """, (resultSet, rowNumber) -> new Source(resultSet.getLong("toilet_id"),
                resultSet.getString("open_time"), resultSet.getString("open_time_detail")), managementNumber);
        if (sources.size() != 1) throw new IllegalStateException("개방시간 원문 화장실을 찾을 수 없습니다.");
        Source source = sources.getFirst();
        String hash = sourceHash(source.openTime(), source.openTimeDetail());
        List<CurrentState> current = jdbc.query("""
                SELECT source_hash,manual_override,parser_version
                  FROM toilet_opening_hours WHERE toilet_id=? FOR UPDATE
                """, (resultSet, rowNumber) -> new CurrentState(resultSet.getString("source_hash"),
                resultSet.getBoolean("manual_override"), resultSet.getString("parser_version")), source.toiletId());
        if (!current.isEmpty()) {
            CurrentState state = current.getFirst();
            if (state.manualOverride()) {
                if (!state.sourceHash().equals(hash)) {
                    jdbc.update("""
                            UPDATE toilet_opening_hours
                               SET source_changed=TRUE,source_hash=?,updated_at=CURRENT_TIMESTAMP
                             WHERE toilet_id=? AND manual_override=TRUE
                            """, hash, source.toiletId());
                }
                return;
            }
            if (state.sourceHash().equals(hash) && OpeningHoursParser.VERSION.equals(state.parserVersion())) return;
        }

        var value = parser.parse(source.openTime(), source.openTimeDetail());
        jdbc.update(UPSERT_SQL, source.toiletId(), hash, value.openingPolicy(), value.open24h(), value.status(),
                value.confidence(), OpeningHoursParser.VERSION, value.holidayPolicy());
        jdbc.update("DELETE FROM toilet_opening_schedule WHERE toilet_id=?", source.toiletId());
        for (Slot slot : value.schedules()) {
            jdbc.update("""
                    INSERT INTO toilet_opening_schedule
                        (toilet_id,day_of_week,slot_index,start_time,end_time,crosses_midnight,is_closed)
                    VALUES (?,?,?,?,?,?,?)
                    """, source.toiletId(), slot.dayOfWeek(), slot.slotIndex(),
                    slot.startTime() == null ? null : Time.valueOf(slot.startTime()),
                    slot.endTime() == null ? null : Time.valueOf(slot.endTime()),
                    slot.crossesMidnight(), slot.closed());
        }
    }

    static String sourceHash(String openTime, String openTimeDetail) {
        String source = clean(openTime) + '\u001f' + clean(openTimeDetail);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("개방시간 원문 해시를 만들 수 없습니다.", exception);
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private record Source(long toiletId, String openTime, String openTimeDetail) {}
    private record CurrentState(String sourceHash, boolean manualOverride, String parserVersion) {}
}
