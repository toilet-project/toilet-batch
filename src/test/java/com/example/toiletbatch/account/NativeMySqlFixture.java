package com.example.toiletbatch.account;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Local synthetic MySQL only: verifies server datadir AND a per-run marker BEFORE creating a schema. */
public final class NativeMySqlFixture {
    private NativeMySqlFixture() { }
    public static boolean enabled() { return System.getenv("ACCOUNT_RETENTION_MYSQL_MARKER") != null; }

    public static DriverManagerDataSource create() {
        String marker = System.getenv("ACCOUNT_RETENTION_MYSQL_MARKER");
        int port = Integer.parseInt(System.getenv("ACCOUNT_RETENTION_MYSQL_PORT"));
        if (marker == null || !marker.matches("[a-f0-9]{10}") || port < 1024 || port > 65535 || port == 3306)
            throw new IllegalStateException("Invalid isolated MySQL fixture configuration");
        String server = "jdbc:mysql://127.0.0.1:" + port + "/";
        // Fresh native MySQL has no named-zone tables; fixed KST offset is equivalent for these 2026 fixtures.
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=%2B09:00"
                + "&forceConnectionTimeZoneToSession=true";
        var admin = new JdbcTemplate(new DriverManagerDataSource(server + "account_retention_fixture_guard" + options, "root", ""));
        String datadir = admin.queryForObject("SELECT @@datadir", String.class);
        if (datadir == null || !datadir.replace('\\', '/').contains("/account-retention-mysql-" + marker + "/data/"))
            throw new IllegalStateException("Not an isolated fixture datadir");
        if (!marker.equals(admin.queryForObject("SELECT marker FROM fixture_guard", String.class)))
            throw new IllegalStateException("Fixture marker mismatch");
        String schema = "account_retention_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        System.out.println("Isolated account fixture schema: " + schema);
        return new DriverManagerDataSource(server + schema + options, "root", "");
    }
}
