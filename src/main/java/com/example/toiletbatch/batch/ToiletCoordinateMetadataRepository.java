package com.example.toiletbatch.batch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
class ToiletCoordinateMetadataRepository {

    static final int LOOKUP_BATCH_SIZE = 250;

    private final JdbcTemplate jdbcTemplate;

    ToiletCoordinateMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Map<String, CoordinateMetadata> findAllByManagementNumbers(List<String> managementNumbers) {
        List<String> numbers = managementNumbers.stream().filter(StringUtils::hasText).distinct().toList();
        Map<String, CoordinateMetadata> metadata = new LinkedHashMap<>();
        for (int start = 0; start < numbers.size(); start += LOOKUP_BATCH_SIZE) {
            List<String> batch = numbers.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, numbers.size()));
            // Match in SQL, but map the result back by request index. Database collation may match
            // a differently cased/padded stored number; Java key normalization must not change that.
            String requested = IntStream.range(0, batch.size())
                    .mapToObj(index -> "SELECT " + index + " AS request_index, CONCAT('', ?) AS mng_no")
                    .collect(Collectors.joining(" UNION ALL "));
            String sql = """
                    SELECT requested.request_index, t.road_address, t.jibun_address,
                           t.latitude, t.longitude, t.coordinate_source, t.geocoded_address_hash, t.geocoded_at
                      FROM (%s) requested JOIN toilet t ON t.mng_no = requested.mng_no
                    """.formatted(requested);
            jdbcTemplate.query(sql, resultSet -> {
                String number = batch.get(resultSet.getInt("request_index"));
                metadata.putIfAbsent(number, readMetadata(resultSet));
            }, batch.toArray());
        }
        return Map.copyOf(metadata);
    }

    private CoordinateMetadata readMetadata(ResultSet resultSet) throws SQLException {
        return new CoordinateMetadata(resultSet.getString("road_address"), resultSet.getString("jibun_address"),
                resultSet.getBigDecimal("latitude"), resultSet.getBigDecimal("longitude"),
                resultSet.getString("coordinate_source"), resultSet.getString("geocoded_address_hash"),
                resultSet.getTimestamp("geocoded_at") == null
                        ? null : resultSet.getTimestamp("geocoded_at").toLocalDateTime());
    }
}
