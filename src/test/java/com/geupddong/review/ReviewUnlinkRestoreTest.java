package com.geupddong.review;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

class ReviewUnlinkRestoreTest {
    @Test void partialGrowthSchemaFailsClosedAndRollsBackTheEntireUnlink() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:unlink_partial_"+UUID.randomUUID()+";MODE=MySQL","sa","");
        try(var connection=ds.getConnection()) {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key VARCHAR(36) UNIQUE,author_user_id BIGINT,author_detached BOOLEAN,version BIGINT)");
            jdbc.execute("CREATE TABLE toilet_review_submission(review_id BIGINT)");
            // A backup from before V40 has none of these tables and is supported. A partial V40 is not.
            jdbc.execute("CREATE TABLE growth_account(user_id BIGINT PRIMARY KEY,total_xp BIGINT)");
            jdbc.update("INSERT INTO toilet_review VALUES(1,?,5,FALSE,0)",ReviewUnlinkJournalTest.A);
            jdbc.update("INSERT INTO toilet_review_submission VALUES(1)");
            var journal=new ReviewUnlinkJournalTest().journal();journal.ensureRecorded(ReviewUnlinkJournalTest.A);
            var restore=new ReviewUnlinkRestore(jdbc,new DataSourceTransactionManager(ds));
            assertThrows(IllegalStateException.class,()->restore.replay(journal.snapshot(),true));
            assertEquals(5L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=1",Long.class));
            assertFalse(jdbc.queryForObject("SELECT author_detached FROM toilet_review WHERE review_id=1",Boolean.class));
            assertEquals(0,jdbc.queryForObject("SELECT version FROM toilet_review WHERE review_id=1",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
        }
    }

    @Test void replayMatchesIncarnationNotReusedNumericIdAndKeepsContent(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:unlink_"+UUID.randomUUID()+";MODE=MySQL","sa","");
        // Keep the connection alive only for this isolated test database.
        try(var connection=ds.getConnection()){
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key VARCHAR(36) UNIQUE,author_user_id BIGINT,author_detached BOOLEAN,version BIGINT,comment VARCHAR(200),rating INT)");
            jdbc.execute("CREATE TABLE toilet_review_submission(review_id BIGINT)");
            jdbc.update("INSERT INTO toilet_review VALUES(1,?,4,FALSE,0,'other incarnation',5),(2,?,5,FALSE,0,'retained text',4)",ReviewUnlinkJournalTest.B,ReviewUnlinkJournalTest.A);
            jdbc.update("INSERT INTO toilet_review_submission VALUES(1),(2)");
            var fixture=new ReviewUnlinkJournalTest();var journal=fixture.journal();journal.ensureRecorded(ReviewUnlinkJournalTest.A);journal.ensureRecorded("cccccccc-1111-1111-1111-111111111111");
            var restore=new ReviewUnlinkRestore(jdbc,new DataSourceTransactionManager(ds));
            var dry=restore.replay(journal.snapshot(),false);assertEquals(1,dry.matched());assertEquals(1,dry.absent());assertEquals(0,dry.unlinked());
            assertEquals(5L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=2",Long.class));
            assertEquals(1,restore.replay(journal.snapshot(),true).unlinked());assertEquals(0,restore.replay(journal.snapshot(),true).unlinked());
            assertNull(jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=2",Long.class));
            assertEquals(4L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=1",Long.class));
            assertEquals("retained text",jdbc.queryForObject("SELECT comment FROM toilet_review WHERE review_id=2",String.class));
            assertEquals(4,jdbc.queryForObject("SELECT rating FROM toilet_review WHERE review_id=2",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT review_id FROM toilet_review_submission",Integer.class));
        }catch(java.sql.SQLException e){throw new IllegalStateException(e);}
    }
}
