package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Maintenance-only tool: default dry-run, optional encrypted LOCAL completion evidence. */
public final class AccountErasureEvidenceCli {
    private AccountErasureEvidenceCli() { }
    public static void main(String[] args) {
        String stage = "arguments";
        try {
            boolean record = args.length == 1 && "--record-completions".equals(args[0]);
            if (args.length > 1 || (args.length == 1 && !record && !"--dry-run".equals(args[0]))) throw new IllegalArgumentException();
            var env = new StandardEnvironment();
            // Keep explicit prerequisites; they do not substitute for an actual shared lease.
            if (!"true".equals(env.getProperty("ERASURE_EVIDENCE_WRITERS_STOPPED"))
                    || !"true".equals(env.getProperty("ERASURE_EVIDENCE_INDEPENDENT_INVENTORY_CONFIRMED"))) throw new IllegalStateException();
            stage = "maintenance-lock";
            try (var maintenance = new AccountMaintenanceGuard(env).acquire()) {
            stage = "independent-inventory";
            var inventory = ErasureIntentInventory.read(Path.of(env.getRequiredProperty("ERASURE_EVIDENCE_INVENTORY_FILE")),
                    env.getRequiredProperty("ERASURE_EVIDENCE_INVENTORY_SHA256"));
            if (!inventory.realm().equals(env.getRequiredProperty("erasure.ledger.realm"))
                    || !inventory.databaseEpoch().equals(env.getRequiredProperty("ERASURE_EVIDENCE_DATABASE_EPOCH"))) throw new IllegalStateException();
            stage = "backup-inventory";
            var clock = Clock.systemUTC();
            var backups = BackupEvidenceInventory.scan(Path.of(env.getRequiredProperty("ERASURE_EVIDENCE_BACKUP_DIRECTORY")), clock.instant());
            stage = "database-guard";
            String url = env.getRequiredProperty("ERASURE_EVIDENCE_DB_URL");
            // Loopback only, no JDBC options/multi-host/redirects supplied by input.
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/toilet_db")) throw new IllegalArgumentException();
            var ds = new DriverManagerDataSource(url + "?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000",
                    env.getRequiredProperty("ERASURE_EVIDENCE_DB_USER"), env.getRequiredProperty("ERASURE_EVIDENCE_DB_PASSWORD"));
            var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(15);
            String uuid = env.getRequiredProperty("ERASURE_EVIDENCE_SERVER_UUID");
            if (!UUID.fromString(uuid).toString().equals(uuid)
                    || !uuid.equals(jdbc.queryForObject("SELECT @@server_uuid", String.class))
                    || !Integer.valueOf(0).equals(jdbc.queryForObject("SELECT @@read_only", Integer.class))) throw new IllegalStateException();
            stage = "ledger-inventory";
            try (var ledger = ErasureLedgerFactory.configured(env)) {
                var records = ledger.readAll(inventory.intentDigests().size());
                inventory.verify(records);
                var store = new AccountErasureEvidenceCollector.CompletionStore() {
                    public ErasureCompletion read(ErasureCompletion expected) { return ledger.readCompletion(expected); }
                    public ErasureCompletion writeOnce(ErasureCompletion proposed) { return ledger.ensureCompletion(proposed); }
                };
                stage = "completion-reconciliation";
                var result = new AccountErasureEvidenceCollector(jdbc, store, clock).collect(records, inventory, backups, record);
                System.out.printf("dryRun=%s records=%d pending=%d absent=%d confirmed=%d wouldRecord=%d unresolvedBackupConfirmations=%d dumpFiles=%d unclassifiedEntries=%d retentionClearance=false%n",
                        !record, result.records(), result.pending(), result.absent(), result.confirmed(), result.wouldRecord(),
                        result.confirmationsWithUnresolvedBackups(), backups.files().size(), backups.unclassifiedEntries());
            }
            }
        } catch (Exception ignored) {
            System.err.println("ERASURE_EVIDENCE_FAILED: " + stage);
            System.exit(1);
        }
    }
}
