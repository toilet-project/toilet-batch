package com.example.toiletbatch.batch;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only the isolated CI MySQL service and a newly created synthetic schema are accepted. */
@EnabledIfEnvironmentVariable(named = "BATCH_METADATA_MYSQL_CI", matches = "true")
class ToiletCoordinateMetadataRepositoryMySqlTest {

    @Test
    void boundedPageQueriesMatchOriginalMySqlPointReads() {
        inSyntheticSchema(ds -> ToiletCoordinateMetadataRepositoryTest.verifyThousandRecordPage(new JdbcTemplate(ds)));
    }

    @Test
    void inputKeysKeepOriginalMatchingAcrossDatabaseCollations() {
        inSyntheticSchema(ds -> {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            for (String collation : new String[] {"utf8mb4_0900_ai_ci", "utf8mb4_unicode_ci", "utf8mb4_bin"}) {
                ToiletCoordinateMetadataRepositoryTest.createTable(
                        jdbc, "VARCHAR(80) CHARACTER SET utf8mb4 COLLATE " + collation);
                ToiletCoordinateMetadataRepositoryTest.verifyInputKeyMatching(jdbc);
                jdbc.execute("DROP TABLE toilet");
            }
        });
    }

    @Test
    void writerStillProtectsLaterAdministratorCorrectionsAndHiddenFacilities() {
        inSyntheticSchema(ds -> new ToiletSyncWriterTest().verifyWriter(ds, false));
    }

    private void inSyntheticSchema(java.util.function.Consumer<DataSource> verification) {
        String base = System.getenv("BATCH_METADATA_MYSQL_URL");
        String password = "synthetic-batch-metadata-only";
        if (!"jdbc:mysql://127.0.0.1:43318".equals(base)) {
            throw new IllegalStateException("Only the isolated batch metadata CI database is permitted");
        }
        String schema = "batch_metadata_ci_" + UUID.randomUUID().toString().replace("-", "");
        String options = "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=%2B09:00"
                + "&forceConnectionTimeZoneToSession=true";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(base + options, "root", password));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            verification.accept(new DriverManagerDataSource(base + "/" + schema + options, "root", password));
        } finally {
            if (!schema.matches("batch_metadata_ci_[a-f0-9]{32}")) {
                throw new IllegalStateException("Synthetic schema cleanup refused");
            }
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
