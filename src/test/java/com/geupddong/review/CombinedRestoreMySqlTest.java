package com.geupddong.review;

import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.account.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Synthetic GitHub-runner MySQL only. It creates and drops one random guarded schema. */
@EnabledIfEnvironmentVariable(named="REVIEW_RESTORE_CI",matches="true")
class CombinedRestoreMySqlTest {
    private static final String TARGET_REVIEW_KEY="00000000-0000-4000-8000-000000000001";
    private static final String ABSENT_REVIEW_KEY="99999999-0000-4000-8000-000000000099";

    @Test void restoredAccountAndReviewUnlinksAreReappliedWithoutDeletingOperationalContent() throws Exception {
        String base=required("REVIEW_RESTORE_CI_URL");
        if(!base.matches("jdbc:mysql://127\\.0\\.0\\.1:43317/?"))throw new IllegalStateException("CI target refused");
        String schema="review_restore_ci_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var root=new DriverManagerDataSource(base+"?allowPublicKeyRetrieval=true&useSSL=false",required("REVIEW_RESTORE_CI_USER"),required("REVIEW_RESTORE_CI_PASSWORD"));
        var admin=new JdbcTemplate(root);admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            var ds=new DriverManagerDataSource(base+"/"+schema+"?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true",
                    required("REVIEW_RESTORE_CI_USER"),required("REVIEW_RESTORE_CI_PASSWORD"));
            var jdbc=new JdbcTemplate(ds);
            String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/combined-review-restore-fixture.sql")).readAllBytes(),StandardCharsets.UTF_8);
            for(String statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement.trim());
            var manager=new DataSourceTransactionManager(ds);
            var account=new AccountErasureRestore(jdbc,manager);
            var accountRecord=new ErasureRecord(1,"synthetic",1,"2000-01-01T00:00",UUID.randomUUID().toString(),"2000-04-01T00:00");
            var fixture=new ReviewUnlinkJournalTest();var journal=fixture.journal();
            journal.ensureRecorded(TARGET_REVIEW_KEY);
            journal.ensureRecorded(ABSENT_REVIEW_KEY);
            var review=new ReviewUnlinkRestore(jdbc,manager);

            assertEquals(1,account.replay(List.of(accountRecord),"synthetic",LocalDateTime.of(2026,9,12,0,0),false).matched());
            var reviewDry=review.replay(journal.snapshot(),false);assertEquals(1,reviewDry.matched());assertEquals(1,reviewDry.absent());
            assertEquals(1,account.replay(List.of(accountRecord),"synthetic",LocalDateTime.of(2026,9,12,0,0),true).erased());
            assertEquals(1,review.replay(journal.snapshot(),true).unlinked());

            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_report",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
            assertEquals("retained synthetic review",jdbc.queryForObject("SELECT content FROM toilet_review WHERE review_id=1",String.class));
            assertEquals(5,jdbc.queryForObject("SELECT satisfaction FROM toilet_review WHERE review_id=1",Integer.class));
            assertNull(jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=1",Long.class));
            assertTrue(jdbc.queryForObject("SELECT author_detached FROM toilet_review WHERE review_id=1",Boolean.class));
            assertEquals(2L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=2",Long.class));
            assertFalse(jdbc.queryForObject("SELECT author_detached FROM toilet_review WHERE review_id=2",Boolean.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT review_id FROM toilet_review_submission",Integer.class));
            assertEquals(0,account.replay(List.of(accountRecord),"synthetic",LocalDateTime.of(2026,9,12,0,0),true).erased());
            assertEquals(0,review.replay(journal.snapshot(),true).unlinked());
        } finally {
            if(!schema.matches("review_restore_ci_[a-f0-9]{20}"))throw new IllegalStateException("CI cleanup refused");
            admin.execute("DROP DATABASE "+schema);
        }
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name);return value;}
}
