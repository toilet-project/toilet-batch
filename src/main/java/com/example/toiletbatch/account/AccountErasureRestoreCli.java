package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Explicit offline restore tool, NEVER a scheduled job or HTTP endpoint. Defaults to dry-run. */
public final class AccountErasureRestoreCli {
    private AccountErasureRestoreCli() { }
    public static void main(String[] args) {
        String stage = "arguments";
        try {
            boolean apply = args.length == 1 && "--apply".equals(args[0]);
            if (args.length > 1 || (args.length == 1 && !apply && !"--dry-run".equals(args[0])))
                throw new IllegalArgumentException();
            var env = new StandardEnvironment();
            if (!"true".equals(env.getProperty("ERASURE_RESTORE_WRITERS_STOPPED")))
                throw new IllegalStateException();
            if (apply && !"true".equals(env.getProperty("ERASURE_RESTORE_REDIS_RESET_CONFIRMED")))
                throw new IllegalStateException();
            String url = env.getRequiredProperty("ERASURE_RESTORE_URL");
            boolean containerIsolated = "true".equals(env.getProperty("ERASURE_RESTORE_CONTAINER_ISOLATED"));
            String schemaPattern = containerIsolated ? "toilet_db" : "erasure_restore_[a-f0-9]{16,32}";
            String restoreHost = "127.0.0.1";
            if (containerIsolated) {
                restoreHost = env.getRequiredProperty("ERASURE_RESTORE_CONTAINER_IP");
                if (!isPrivateIpv4(restoreHost)) throw new IllegalArgumentException();
            }
            var match = Pattern.compile("jdbc:mysql://" + Pattern.quote(restoreHost) + ":([0-9]{4,5})/" + schemaPattern).matcher(url);
            if (!match.matches()) throw new IllegalArgumentException();
            int port = Integer.parseInt(match.group(1));
            if (port < 1024 || port == 3306 || port > 65535) throw new IllegalArgumentException();
            if (containerIsolated && port != 43317) throw new IllegalArgumentException();
            String marker = env.getRequiredProperty("ERASURE_RESTORE_MARKER");
            if (!marker.matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
            var ds = new DriverManagerDataSource(url + "?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true",
                    env.getRequiredProperty("ERASURE_RESTORE_DB_USER"), env.getRequiredProperty("ERASURE_RESTORE_DB_PASSWORD"));
            var jdbc = new JdbcTemplate(ds);
            stage = "database-guard";
            if (containerIsolated) {
                String serverUuid = env.getRequiredProperty("ERASURE_RESTORE_SERVER_UUID");
                if (!serverUuid.matches("[a-f0-9-]{36}")
                        || !serverUuid.equals(jdbc.queryForObject("SELECT @@server_uuid", String.class))
                        || !"OFF".equals(jdbc.queryForObject("SELECT @@event_scheduler", String.class))
                        || !Integer.valueOf(0).equals(jdbc.queryForObject("SELECT @@log_bin", Integer.class)))
                    throw new IllegalStateException();
            }
            if (!marker.equals(jdbc.queryForObject("SELECT marker FROM erasure_restore_guard", String.class)))
                throw new IllegalStateException();
            stage = "ledger-configuration";
            try (var ledger = ErasureLedgerFactory.configured(env)) {
                int expected = Integer.parseInt(env.getRequiredProperty("ERASURE_RESTORE_EXPECTED_OBJECTS"));
                stage = "ledger-snapshot";
                var checkpointStore = GitHubErasureCheckpointStore.configured(
                        env.getRequiredProperty("ERASURE_CHECKPOINT_GITHUB_TOKEN"));
                var records = VerifiedErasureSnapshot.read(ledger, checkpointStore,
                        env.getRequiredProperty("erasure.ledger.realm"),
                        env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH"), java.time.Clock.systemUTC());
                if (records.size() != expected) throw new IllegalStateException();
                stage = "database-replay";
                var result = new AccountErasureRestore(jdbc, new DataSourceTransactionManager(ds)).replay(
                        records, env.getRequiredProperty("erasure.ledger.realm"), LocalDateTime.now(ZoneId.of("Asia/Seoul")), apply);
                System.out.printf("dryRun=%s records=%d matched=%d absent=%d erased=%d%n",
                        !apply, result.records(), result.matched(), result.absent(), result.erased());
            }
        } catch (Exception ignored) {
            System.err.println("ERASURE_RESTORE_FAILED: " + stage);
            System.exit(1);
        }
    }

    static boolean isPrivateIpv4(String value) {
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        for (int i=0;i<4;i++) {
            if (parts[i].length()>1 && parts[i].startsWith("0")) return false;
            octets[i]=Integer.parseInt(parts[i]);
            if (octets[i]>255) return false;
        }
        return octets[0]==10 || (octets[0]==172 && octets[1]>=16 && octets[1]<=31)
                || (octets[0]==192 && octets[1]==168);
    }
}
