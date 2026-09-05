package com.example.toiletbatch.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import static com.example.toiletbatch.region.RegionModel.*;

public final class RegionRepository {
    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();
    private static final String SOURCE_COLUMNS = "toilet_id, road_address, jibun_address, latitude, longitude";
    private final String sampleFilter;
    public RegionRepository(DataSource dataSource) { this(dataSource, List.of()); }
    public RegionRepository(DataSource dataSource, List<Long> sampleIds) {
        if (sampleIds.size() > 100 || sampleIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Sample IDs must be at most 100 positive integers");
        this.dataSource = dataSource;
        sampleFilter = sampleIds.isEmpty() ? "" : " AND t.toilet_id IN (" + sampleIds.stream().distinct().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + ")";
    }

    public long count() throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM toilet")) {
            rs.next(); return rs.getLong(1);
        }
    }
    public List<Source> page(long after, int size) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("SELECT " + SOURCE_COLUMNS + " FROM toilet t WHERE toilet_id > ?" + sampleFilter + " ORDER BY toilet_id LIMIT ?")) {
            s.setLong(1, after); s.setInt(2, size);
            try (var rs = s.executeQuery()) {
                List<Source> items = new ArrayList<>();
                while (rs.next()) items.add(source(rs));
                return items;
            }
        }
    }
    public record Snapshot(Source source, Result result) { }
    public List<Snapshot> pageWithRegions(long after, int size) throws Exception {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                SELECT t.toilet_id, t.road_address, t.jibun_address, t.latitude, t.longitude, r.result_json
                FROM toilet t LEFT JOIN toilet_region r ON r.toilet_id=t.toilet_id
                WHERE t.toilet_id > ?
                """ + sampleFilter + " ORDER BY t.toilet_id LIMIT ?")) {
            s.setLong(1, after); s.setInt(2, size);
            try (var rs = s.executeQuery()) {
                List<Snapshot> rows = new ArrayList<>();
                while (rs.next()) {
                    String payload = rs.getString("result_json");
                    rows.add(new Snapshot(source(rs), payload == null ? null : json.readValue(payload, Result.class)));
                }
                return rows;
            }
        }
    }
    private Source source(ResultSet rs) throws SQLException {
        return new Source(rs.getLong("toilet_id"), rs.getString("road_address"), rs.getString("jibun_address"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"));
    }
    public Result stored(long id) throws Exception {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("SELECT result_json FROM toilet_region WHERE toilet_id = ?")) {
            s.setLong(1, id);
            try (var rs = s.executeQuery()) { return rs.next() ? json.readValue(rs.getString(1), Result.class) : null; }
        }
    }
    /** Dedicated MySQL advisory connection keeps different journal paths/processes from racing writes. */
    public AutoCloseable lock() throws SQLException {
        Connection c = dataSource.getConnection();
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT GET_LOCK('toilet_region_normalization', 0)")) {
            if (!rs.next() || rs.getInt(1) != 1) throw new SQLException("Another region worker is running");
        } catch (SQLException e) { c.close(); throw e; }
        return () -> {
            try (var s = c.createStatement()) { s.execute("SELECT RELEASE_LOCK('toilet_region_normalization')"); }
            finally { c.close(); }
        };
    }
    public record Applied(boolean conflict, boolean coordinatesFilled, Result result) { }

    /** External HTTP has completed before this short transaction. Recheck source under the row lock. */
    public Applied apply(Result result, boolean fillMissing) throws Exception {
        try (var c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit(); c.setAutoCommit(false);
            try {
                Source current;
                try (var s = c.prepareStatement("SELECT " + SOURCE_COLUMNS + " FROM toilet WHERE toilet_id = ? FOR UPDATE")) {
                    s.setLong(1, result.source().toiletId());
                    try (var rs = s.executeQuery()) { current = rs.next() ? source(rs) : null; }
                }
                if (current == null || !current.hash().equals(result.source().hash())) {
                    c.rollback(); return new Applied(true, false, result);
                }
                // Keep the input snapshot before optional missing-coordinate filling changes its hash.
                saveHistory(c, result);
                boolean filled = fillMissing && current.latitude() == null && current.longitude() == null
                        && result.status() == Status.VERIFIED && result.evaluated() != null && result.evaluated().valid()
                        && !"NONE".equals(result.fallback());
                if (filled) {
                    try (var s = c.prepareStatement("UPDATE toilet SET latitude=?, longitude=?, coordinate_source=?, geocoded_at=? WHERE toilet_id=? AND latitude IS NULL AND longitude IS NULL")) {
                        s.setBigDecimal(1, result.evaluated().latitude()); s.setBigDecimal(2, result.evaluated().longitude());
                        s.setString(3, "GEOCODED_" + result.fallback());
                        s.setObject(4, LocalDateTime.ofInstant(Instant.ofEpochMilli(result.checkedEpochMillis()), ZoneId.of("Asia/Seoul")));
                        s.setLong(5, current.toiletId());
                        if (s.executeUpdate() != 1) throw new SQLException("Coordinate fill conflict");
                    }
                    result = result.withSource(current.withPoint(result.evaluated()));
                }
                save(c, result);
                c.commit(); return new Applied(false, filled, result);
            } catch (Exception e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(auto); }
        }
    }
    private void save(Connection c, Result r) throws Exception {
        String columns = "toilet_id,sido_name,sido_code,sigungu_name,sigungu_code,city_name,district_name,legal_dong_code,administrative_dong_code,region_source,status,reason,source_hash,source_latitude,source_longitude,source_road_address,source_jibun_address,evaluated_latitude,evaluated_longitude,result_json,checked_at";
        String[] names = columns.split(",");
        String update = java.util.Arrays.stream(names).skip(1).map(n -> n + "=VALUES(" + n + ")").collect(java.util.stream.Collectors.joining(","));
        Region region = r.region();
        Object[] values = {r.source().toiletId(), region == null ? null : region.sidoName(), region == null ? null : region.sidoCode(),
                region == null ? null : region.sigunguName(), region == null ? null : region.sigunguCode(),
                region == null ? null : region.cityName(), region == null ? null : region.districtName(),
                region == null ? null : region.legalDongCode(), region == null ? null : region.administrativeDongCode(),
                "KAKAO_COORD2REGIONCODE_B", r.status().name(), r.reason(), r.source().hash(), r.source().latitude(), r.source().longitude(),
                r.source().roadAddress(), r.source().jibunAddress(), r.evaluated() == null ? null : r.evaluated().latitude(),
                r.evaluated() == null ? null : r.evaluated().longitude(), json.writeValueAsString(r),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(r.checkedEpochMillis()), ZoneId.of("Asia/Seoul"))};
        try (var s = c.prepareStatement("INSERT INTO toilet_region (" + columns + ") VALUES (" + String.join(",", java.util.Collections.nCopies(names.length, "?")) + ") ON DUPLICATE KEY UPDATE " + update)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }
    private void saveHistory(Connection c, Result r) throws Exception {
        try (var s = c.prepareStatement("""
                INSERT INTO toilet_region_assessment_history
                (toilet_id,source_hash,algorithm_version,status,reason,result_json,checked_epoch_millis,checked_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE assessment_id=assessment_id
                """)) {
            s.setLong(1, r.source().toiletId()); s.setString(2, r.source().hash());
            s.setString(3, r.algorithmVersion()); s.setString(4, r.status().name()); s.setString(5, r.reason());
            s.setString(6, json.writeValueAsString(r)); s.setLong(7, r.checkedEpochMillis());
            s.setObject(8, LocalDateTime.ofInstant(Instant.ofEpochMilli(r.checkedEpochMillis()), ZoneId.of("Asia/Seoul")));
            s.executeUpdate();
        }
    }
}
