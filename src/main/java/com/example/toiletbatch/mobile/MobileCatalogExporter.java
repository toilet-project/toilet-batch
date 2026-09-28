package com.example.toiletbatch.mobile;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Explicit public columns only; no ORM entities or user tables are exported. */
public final class MobileCatalogExporter {
    private final DataSource dataSource;
    public MobileCatalogExporter(DataSource dataSource) { this.dataSource = dataSource; }

    public void export(Path output) throws SQLException, IOException {
        try (var input = MobileCatalogExporter.class.getResourceAsStream("/mobile-catalog/export.sql")) {
            if (input == null) throw new IOException("Missing public catalog query resource");
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (var connection = dataSource.getConnection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                    for (String query : sql.split(";")) {
                        if (query.isBlank()) continue;
                        try (var statement = connection.createStatement()) {
                            statement.setQueryTimeout(300);
                            statement.setFetchSize(512);
                            try (var rows = statement.executeQuery(query.strip())) {
                                while (rows.next()) { writer.write(rows.getString(1)); writer.newLine(); }
                            }
                        }
                    }
                } finally {
                    // This read-only transaction never commits or changes the source database.
                    connection.rollback();
                }
            }
        }
    }
}
