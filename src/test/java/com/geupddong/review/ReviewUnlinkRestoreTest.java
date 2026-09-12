package com.geupddong.review;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

class ReviewUnlinkRestoreTest {
    @Test void replayMatchesIncarnationNotReusedNumericIdAndKeepsContent(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:unlink_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key VARCHAR(36) UNIQUE,author_user_id BIGINT,author_detached BOOLEAN,version BIGINT,comment VARCHAR(200),rating INT)");
        jdbc.execute("CREATE TABLE toilet_review_submission(review_id BIGINT)");
        jdbc.update("INSERT INTO toilet_review VALUES(1,?,4,FALSE,0,'other incarnation',5),(2,?,5,FALSE,0,'retained text',4)",ReviewUnlinkJournalTest.B,ReviewUnlinkJournalTest.A);
        jdbc.update("INSERT INTO toilet_review_submission VALUES(1),(2)");
        var fixture=new ReviewUnlinkJournalTest();var journal=fixture.journal();journal.ensureRecorded(ReviewUnlinkJournalTest.A);journal.ensureRecorded("cccccccc-1111-1111-1111-111111111111");
        var restore=new ReviewUnlinkRestore(jdbc,new DataSourceTransactionManager(ds));
        var dry=restore.replay(journal.snapshot(),false);assertEquals(1,dry.matched());assertEquals(1,dry.absent());assertEquals(0,dry.unlinked());
        assertEquals(1,restore.replay(journal.snapshot(),true).unlinked());assertEquals(0,restore.replay(journal.snapshot(),true).unlinked());
        assertNull(jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=2",Long.class));
        assertEquals(4L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=1",Long.class));
        assertEquals("retained text",jdbc.queryForObject("SELECT comment FROM toilet_review WHERE review_id=2",String.class));
        assertEquals(4,jdbc.queryForObject("SELECT rating FROM toilet_review WHERE review_id=2",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT review_id FROM toilet_review_submission",Integer.class));
    }
}
