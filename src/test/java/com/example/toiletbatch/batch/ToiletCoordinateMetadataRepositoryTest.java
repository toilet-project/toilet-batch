package com.example.toiletbatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;

class ToiletCoordinateMetadataRepositoryTest {

    @Test
    void thousandRecordsMatchPointReadsWithFourDatabaseQueries() {
        verifyThousandRecordPage(database());
    }

    @Test
    void skipsEmptyKeysAndReadsFreshMetadataOnTheNextPage() {
        JdbcTemplate jdbc = spy(database());
        createTable(jdbc, "VARCHAR(80)");
        ToiletCoordinateMetadataRepository repository = new ToiletCoordinateMetadataRepository(jdbc);
        clearInvocations(jdbc);
        assertEquals(Map.of(), repository.findAllByManagementNumbers(Arrays.asList(null, "", " \t")));
        verify(jdbc, times(0)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        jdbc.update("INSERT INTO toilet(mng_no,coordinate_source) VALUES('A','LEGACY')");
        Map<String, CoordinateMetadata> first = repository.findAllByManagementNumbers(List.of("A"));
        jdbc.update("UPDATE toilet SET latitude=37.5,longitude=127.5,coordinate_source='ADMIN_CONFIRMED' WHERE mng_no='A'");
        Map<String, CoordinateMetadata> second = repository.findAllByManagementNumbers(List.of("A"));
        assertEquals("LEGACY", first.get("A").source());
        assertEquals("ADMIN_CONFIRMED", second.get("A").source());
        assertEquals(new BigDecimal("37.5000000"), second.get("A").latitude());
    }

    @Test
    void mapsCaseInsensitiveDatabaseMatchesBackToEachOriginalInputKey() {
        JdbcTemplate jdbc = database();
        createTable(jdbc, "VARCHAR_IGNORECASE(80)");
        verifyInputKeyMatching(jdbc);
    }

    static void verifyThousandRecordPage(JdbcTemplate database) {
        JdbcTemplate jdbc = spy(database);
        createTable(jdbc, "VARCHAR(80)");
        List<String> numbers = IntStream.range(0, 1000).mapToObj(index -> "fixture-" + index).toList();
        List<Object[]> rows = IntStream.range(0, numbers.size()).mapToObj(index -> new Object[] {
                numbers.get(index), index % 2 == 0 ? "기존 도로 " + index : null, "기존 지번 " + index,
                index % 3 == 0 ? null : new BigDecimal("37.5000000"),
                index % 4 == 0 ? null : new BigDecimal("127.5000000"),
                index % 5 == 0 ? "ADMIN_CONFIRMED" : "GEOCODED_ROAD", "a".repeat(64),
                index % 2 == 0 ? LocalDateTime.of(2026, 10, 4, 2, 0) : null
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO toilet(mng_no,road_address,jibun_address,latitude,longitude,
                                   coordinate_source,geocoded_address_hash,geocoded_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, rows);
        Map<String, CoordinateMetadata> expected = pointReads(database, numbers);
        assertEquals(1000, expected.size());
        clearInvocations(jdbc);

        Map<String, CoordinateMetadata> actual = new ToiletCoordinateMetadataRepository(jdbc)
                .findAllByManagementNumbers(numbers);

        assertEquals(expected, actual);
        verify(jdbc, times(4)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    static void verifyInputKeyMatching(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO toilet(mng_no,road_address,latitude,longitude,coordinate_source)
                VALUES('Case-Key','관리자 주소',37.5,127.5,'ADMIN_CONFIRMED'),
                      ('Café','기존 주소',36.5,NULL,'GEOCODED_LEGACY'),
                      ('한글-번호',NULL,NULL,NULL,'LEGACY')
                """);
        List<String> numbers = new ArrayList<>(Arrays.asList(
                "Case-Key", "case-key", "Case-Key ", "Café", "Cafe", "한글-번호", "없음", null, "", " \t"));
        // Do not truncate an overlong incoming key into an existing management number.
        numbers.add("Case-Key" + "x".repeat(100));
        numbers.add("Case-Key");
        Map<String, CoordinateMetadata> expected = pointReads(jdbc, numbers);
        Map<String, CoordinateMetadata> actual = new ToiletCoordinateMetadataRepository(jdbc)
                .findAllByManagementNumbers(numbers);
        assertEquals(expected, actual);
        assertEquals("ADMIN_CONFIRMED", actual.get("Case-Key").source());
        assertFalse(actual.containsKey("Case-Key" + "x".repeat(100)));
        assertFalse(actual.containsKey("없음"));
    }

    static void createTable(JdbcTemplate jdbc, String managementNumberType) {
        jdbc.execute("""
                CREATE TABLE toilet(mng_no %s NOT NULL PRIMARY KEY,
                    road_address VARCHAR(255),jibun_address VARCHAR(255),
                    latitude DECIMAL(10,7),longitude DECIMAL(10,7),coordinate_source VARCHAR(30),
                    geocoded_address_hash CHAR(64),geocoded_at TIMESTAMP)
                """.formatted(managementNumberType));
    }

    // The pre-optimization WHERE predicate is the oracle for database collation and null mapping.
    private static Map<String, CoordinateMetadata> pointReads(JdbcTemplate jdbc, List<String> numbers) {
        Map<String, CoordinateMetadata> result = new LinkedHashMap<>();
        for (String number : numbers) {
            if (!StringUtils.hasText(number)) continue;
            jdbc.query("""
                    SELECT road_address,jibun_address,latitude,longitude,coordinate_source,
                           geocoded_address_hash,geocoded_at FROM toilet WHERE mng_no=?
                    """, rs -> {
                result.putIfAbsent(number, new CoordinateMetadata(
                        rs.getString("road_address"), rs.getString("jibun_address"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                        rs.getString("coordinate_source"), rs.getString("geocoded_address_hash"),
                        rs.getTimestamp("geocoded_at") == null ? null : rs.getTimestamp("geocoded_at").toLocalDateTime()));
            }, number);
        }
        return result;
    }

    private static JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:coordinate-metadata-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }
}
