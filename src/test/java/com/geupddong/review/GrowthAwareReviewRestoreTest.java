package com.geupddong.review;

import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.growth.GrowthLedger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Restoring a pre-unlink backup must not re-identify anonymous reviews through their XP source. */
class GrowthAwareReviewRestoreTest {
    static final String FIRST="00000000-0000-4000-8000-000000000001";
    static final String DUPLICATE="00000000-0000-4000-8000-000000000002";
    static final String OTHER="00000000-0000-4000-8000-000000000003";
    JdbcTemplate jdbc;
    ReviewUnlinkRestore restore;
    ReviewUnlinkJournal journal;

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:growth_restore_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(30),auth_version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,visibility_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key CHAR(36) UNIQUE,toilet_id BIGINT,author_user_id BIGINT,author_detached BOOLEAN,version BIGINT,satisfaction INT,cleanliness INT,paper_available BOOLEAN,created_at DATETIME)");
        jdbc.execute("CREATE TABLE toilet_review_submission(user_id BIGINT,review_id BIGINT)");
        jdbc.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,status VARCHAR(30),sigungu_code CHAR(5))");
        jdbc.execute("CREATE TABLE region_sigungu_reference(sigungu_code CHAR(5) PRIMARY KEY,sido_code CHAR(2),sido_name VARCHAR(50),sigungu_name VARCHAR(100),display_name VARCHAR(160),is_active BOOLEAN)");
        String ddl=new ClassPathResource("db/migration/V40__member_growth.sql").getContentAsString(StandardCharsets.UTF_8).replace("BOOLEAN","TINYINT");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(ds);
        jdbc.update("INSERT INTO app_user(user_id,status) VALUES(1,'ACTIVE')");
        jdbc.update("INSERT INTO toilet VALUES(1,'VISIBLE'),(2,'VISIBLE')");
        jdbc.update("INSERT INTO current_toilet_region VALUES(1,'VERIFIED','30140'),(2,'VERIFIED','30140')");
        jdbc.update("INSERT INTO region_sigungu_reference VALUES('30140','30','대전','중구','중구',TRUE)");
        jdbc.update("INSERT INTO toilet_review VALUES(1,?,1,1,FALSE,0,4,5,TRUE,'2026-10-01 12:00:00'),(2,?,1,1,FALSE,0,5,5,TRUE,'2026-10-02 12:00:00'),(3,?,2,1,FALSE,0,4,4,TRUE,'2026-10-03 12:00:00')",FIRST,DUPLICATE,OTHER);
        jdbc.update("INSERT INTO toilet_review_submission VALUES(1,1),(1,2),(1,3)");
        jdbc.update("""
                INSERT INTO growth_policy_snapshot(policy_version,initialized_at,target_count,review_xp,checkin_xp,district_xp,
                    bronze_xp,silver_xp,gold_xp,bronze_facilities,silver_facilities,gold_facilities,silver_coverage_percent,gold_coverage_percent)
                VALUES('2026-10-v1','2026-10-04 12:00:00',1,10,2,20,30,50,100,3,10,30,50,100)
                """);
        jdbc.update("INSERT INTO growth_policy_target VALUES('2026-10-v1','30140','30','대전','중구')");
        var manager=new DataSourceTransactionManager(ds);
        new TransactionTemplate(manager).executeWithoutResult(tx->GrowthLedger.reconcile(jdbc,1,
                GrowthLedger.policy(jdbc,"2026-10-v1"),"BACKFILL",Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"),ZoneOffset.UTC)));
        restore=new ReviewUnlinkRestore(jdbc,manager);
        journal=new ReviewUnlinkJournalTest().journal();
        assertEquals(40L,GrowthLedger.totalXp(jdbc,1));
    }

    @Test void unlinkKeepsAnotherValidReviewForSameFacilityThenRevokesOnlyLostContribution() {
        journal.ensureRecorded(FIRST);
        assertEquals(1,restore.replay(journal.snapshot(),true).unlinked());
        assertEquals(40L,GrowthLedger.totalXp(jdbc,1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_review_evidence WHERE review_key=?",Integer.class,FIRST));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE active=TRUE AND award_key='T:1'",Integer.class));
        journal.ensureRecorded(DUPLICATE);
        assertEquals(1,restore.replay(journal.snapshot(),true).unlinked());
        assertEquals(30L,GrowthLedger.totalXp(jdbc,1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_review_evidence WHERE review_key IN (?,?)",Integer.class,FIRST,DUPLICATE));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE award_key='T:1'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE active=FALSE AND award_key IS NULL AND scrubbed_at IS NOT NULL",Integer.class));
        assertEquals(List.of(OTHER),jdbc.query("SELECT review_key FROM growth_review_evidence",(rs,n)->rs.getString(1)));
        assertEquals(30L,jdbc.queryForObject("SELECT SUM(delta_xp) FROM growth_xp_event WHERE user_id=1",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event WHERE delta_xp=-10",Integer.class));
        int events=jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event",Integer.class);
        assertEquals(0,restore.replay(journal.snapshot(),true).unlinked());
        assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
    }

    @Test void alreadyAnonymousRowsAndMissingReviewRowsStillScrubStaleEvidence() {
        jdbc.update("UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE WHERE review_id=1");
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id IN (1,2)");
        jdbc.update("DELETE FROM toilet_review WHERE review_id=2");
        journal.ensureRecorded(FIRST);journal.ensureRecorded(DUPLICATE);
        var dry=restore.replay(journal.snapshot(),false);
        assertEquals(1,dry.matched());assertEquals(1,dry.absent());
        assertEquals(40L,GrowthLedger.totalXp(jdbc,1));
        var applied=restore.replay(journal.snapshot(),true);
        assertEquals(0,applied.unlinked());
        assertEquals(30L,GrowthLedger.totalXp(jdbc,1));
        assertEquals(List.of(OTHER),jdbc.query("SELECT review_key FROM growth_review_evidence",(rs,n)->rs.getString(1)));
        assertNull(jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=1",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=3",Long.class));
    }
}
