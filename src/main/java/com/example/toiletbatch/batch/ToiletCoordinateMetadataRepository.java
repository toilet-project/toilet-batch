package com.example.toiletbatch.batch;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ToiletCoordinateMetadataRepository {

    private static final String FIND_SQL = """
            SELECT road_address, jibun_address, latitude, longitude,
                   coordinate_source, geocoded_address_hash, geocoded_at
              FROM toilet
             WHERE mng_no = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    ToiletCoordinateMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<CoordinateMetadata> findByManagementNumber(String managementNumber) {
        return jdbcTemplate.query(
                FIND_SQL,
                resultSet -> resultSet.next()
                        ? Optional.of(new CoordinateMetadata(
                        resultSet.getString("road_address"),
                        resultSet.getString("jibun_address"),
                        resultSet.getBigDecimal("latitude"),
                        resultSet.getBigDecimal("longitude"),
                        resultSet.getString("coordinate_source"),
                        resultSet.getString("geocoded_address_hash"),
                        resultSet.getTimestamp("geocoded_at") == null
                                ? null : resultSet.getTimestamp("geocoded_at").toLocalDateTime()
                )) : Optional.empty(),
                managementNumber
        );
    }
}
