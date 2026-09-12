package com.example.toiletbatch.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geupddong.account.*;
import com.geupddong.review.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Explicit offline review-unlink replay. Never a scheduler, HTTP endpoint, review/content deletion tool or account eraser. */
public final class ReviewUnlinkRestoreCli {
    private ReviewUnlinkRestoreCli() { }
    public static void main(String[] args) {
        String stage="arguments";
        try {
            boolean apply=args.length==1&&"--apply".equals(args[0]);
            if(args.length>1||(args.length==1&&!apply&&!"--dry-run".equals(args[0])))throw new IllegalArgumentException();
            var env=new StandardEnvironment();
            if(!"true".equals(env.getProperty("ERASURE_RESTORE_WRITERS_STOPPED")))throw new IllegalStateException();
            if(apply&&!"true".equals(env.getProperty("ERASURE_RESTORE_REDIS_RESET_CONFIRMED")))throw new IllegalStateException();
            if(!"LOCAL".equals(env.getRequiredProperty("erasure.ledger.provider"))
                    ||!"true".equals(env.getRequiredProperty("erasure.ledger.local-acceptance-verified")))throw new IllegalStateException();
            String url=env.getRequiredProperty("ERASURE_RESTORE_URL");
            boolean containerIsolated="true".equals(env.getProperty("ERASURE_RESTORE_CONTAINER_ISOLATED"));
            String schemaPattern=containerIsolated?"toilet_db":"erasure_restore_[a-f0-9]{16,32}";
            String restoreHost="127.0.0.1";
            if(containerIsolated){restoreHost=env.getRequiredProperty("ERASURE_RESTORE_CONTAINER_IP");if(!AccountErasureRestoreCli.isPrivateIpv4(restoreHost))throw new IllegalArgumentException();}
            var match=Pattern.compile("jdbc:mysql://"+Pattern.quote(restoreHost)+":([0-9]{4,5})/"+schemaPattern).matcher(url);
            if(!match.matches())throw new IllegalArgumentException();
            int port=Integer.parseInt(match.group(1));
            if(port<1024||port==3306||port>65535||(containerIsolated&&port!=43317))throw new IllegalArgumentException();
            String marker=env.getRequiredProperty("ERASURE_RESTORE_MARKER");if(!marker.matches("[a-f0-9]{32}"))throw new IllegalArgumentException();
            var ds=new DriverManagerDataSource(url+"?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true",
                    env.getRequiredProperty("ERASURE_RESTORE_DB_USER"),env.getRequiredProperty("ERASURE_RESTORE_DB_PASSWORD"));
            var jdbc=new JdbcTemplate(ds);
            stage="database-guard";
            if(containerIsolated){String serverUuid=env.getRequiredProperty("ERASURE_RESTORE_SERVER_UUID");
                if(!serverUuid.matches("[a-f0-9-]{36}")||!serverUuid.equals(jdbc.queryForObject("SELECT @@server_uuid",String.class))
                        ||!"OFF".equals(jdbc.queryForObject("SELECT @@event_scheduler",String.class))
                        ||!Integer.valueOf(0).equals(jdbc.queryForObject("SELECT @@log_bin",Integer.class)))throw new IllegalStateException();}
            if(!marker.equals(jdbc.queryForObject("SELECT marker FROM erasure_restore_guard",String.class)))throw new IllegalStateException();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('toilet_review','toilet_review_submission')",Integer.class)!=2
                    ||jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='toilet_review' AND column_name='review_key'",Integer.class)!=1
                    ||jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='toilet_review' AND column_name='review_key' AND non_unique=0",Integer.class)!=1)
                throw new IllegalStateException();
            stage="review-ledger-configuration";
            var keys=new ObjectMapper().readValue(env.getRequiredProperty("erasure.ledger.keys-json"),new TypeReference<Map<String,String>>(){});
            var directory=Path.of(env.getRequiredProperty("REVIEW_UNLINK_DIRECTORY")).toAbsolutePath().normalize();
            var accountDirectory=Path.of(env.getRequiredProperty("erasure.ledger.local-directory")).toAbsolutePath().normalize();
            if(directory.startsWith(accountDirectory)||accountDirectory.startsWith(directory))throw new IllegalStateException();
            try(var objects=new FileErasureObjectStore(directory,ReviewUnlinkRecord.REALM,env.getRequiredProperty("REVIEW_UNLINK_STORE_ID"));
                var journal=new ReviewUnlinkJournal(objects,new ErasureCipher(env.getRequiredProperty("erasure.ledger.active-key-id"),keys),
                        ReviewCheckpointStore.configured(env.getRequiredProperty("ERASURE_CHECKPOINT_GITHUB_TOKEN")),work->work.run(),
                        env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH"),Clock.systemUTC())){
                stage="review-ledger-snapshot";var snapshot=journal.snapshot();
                int expected=Integer.parseInt(env.getRequiredProperty("REVIEW_RESTORE_EXPECTED_OBJECTS"));
                if(snapshot.records().size()!=expected)throw new IllegalStateException();
                stage="review-database-replay";
                var result=new ReviewUnlinkRestore(jdbc,new DataSourceTransactionManager(ds)).replay(snapshot,apply);
                System.out.printf("reviewDryRun=%s records=%d matched=%d absent=%d unlinked=%d%n",
                        !apply,result.records(),result.matched(),result.absent(),result.unlinked());
            }
        }catch(Exception ignored){System.err.println("REVIEW_RESTORE_FAILED: "+stage);System.exit(1);}
    }
}
