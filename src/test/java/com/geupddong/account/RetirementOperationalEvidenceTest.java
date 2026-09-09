package com.geupddong.account;

import com.example.toiletbatch.account.ErasureRetentionReviewPolicy;
import com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetirementOperationalEvidenceTest {
    @TempDir Path directory;
    final String id="00000000-0000-0000-0000-000000000001",scope="a".repeat(64);
    final Instant now=Instant.parse("2026-10-10T00:00:00Z"),absent=now.minusSeconds(33*86400);
    final ErasureRecord record=new ErasureRecord(1,"test",1,"2026-01-01T00:00",id,"2026-04-01T00:00");
    final ErasureCompletion completion=ErasureCompletion.observed(record,id,absent);
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc=spy(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")));
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,created_at TIMESTAMP)");
        doReturn(id).when(jdbc).queryForObject("SELECT @@server_uuid",String.class);
        doReturn("toilet_db").when(jdbc).queryForObject("SELECT DATABASE()",String.class);
    }
    RetirementOperationalEvidence.Audit audit(Instant checked,Instant cutoff) {
        var values=new TreeMap<String,String>();RetirementOperationalEvidence.SOURCES.forEach(s->values.put(s,cutoff.toString()));
        return new RetirementOperationalEvidence.Audit(1,"test",id,id,checked.toString(),"LOCAL_RETENTION_V1",scope,now.toString(),now.minusSeconds(100*86400).toString(),values);
    }
    RetirementOperationalEvidence source(RetirementOperationalEvidence.Audit audit) {
        return new RetirementOperationalEvidence(jdbc,"test",id,id,scope,()->audit,Clock.fixed(now,ZoneOffset.UTC),()->{});
    }
    @Test void liveAbsenceAndEveryAuthenticatedScopeAreRequiredForClearance() {
        var result=source(audit(now,absent)).current(record,completion);
        assertEquals(Status.REVIEW_CANDIDATE,new ErasureRetentionReviewPolicy().evaluate(now,result).status());
    }
    @Test void aCopyBeforeAbsenceIsAHoldNotAutomaticClearance() {
        var result=source(audit(now,absent.minusSeconds(1))).current(record,completion);
        assertEquals(Status.HOLD_MISSING_EVIDENCE,new ErasureRetentionReviewPolicy().evaluate(now,result).status());
        assertFalse(result.verified().contains(Requirement.PRE_ERASURE_COPIES_AND_LOGS_REMOVED));
    }
    @Test void existingOrReusedUserIdCannotBeCertifiedAbsent() {
        jdbc.update("INSERT INTO app_user VALUES(1,?)",LocalDateTime.parse("2026-09-01T00:00"));
        assertThrows(IllegalStateException.class,()->source(audit(now,absent)).current(record,completion));
    }
    @Test void staleAuditAndWrongDatabaseNeverPass() {
        assertThrows(IllegalStateException.class,()->source(audit(now.minusSeconds(601),absent)).current(record,completion));
        doReturn("other_database").when(jdbc).queryForObject("SELECT DATABASE()",String.class);
        assertThrows(IllegalStateException.class,()->source(audit(now,absent)).current(record,completion));
    }
    @Test void missingCopyLocationCannotBeFilledInAsEmpty() {
        var values=new TreeMap<>(audit(now,absent).clearedThrough());values.remove("OFFHOST_COPIES");
        assertThrows(IllegalStateException.class,()->new RetirementOperationalEvidence.Audit(1,"test",id,id,now.toString(),"LOCAL_RETENTION_V1",scope,now.toString(),absent.toString(),values));
    }
    @Test void wrongIdentityOrScopeNeverBorrowsAnotherReview() {
        var wrong=ErasureCompletion.observed(new ErasureRecord(1,"test",2,record.userCreatedAt(),new UUID(0,2).toString(),record.eligibleAt()),id,absent);
        assertThrows(IllegalStateException.class,()->source(audit(now,absent)).current(record,wrong));
        var s=new RetirementOperationalEvidence(jdbc,"test",id,id,"b".repeat(64),()->audit(now,absent),Clock.fixed(now,ZoneOffset.UTC),()->{});
        assertThrows(IllegalStateException.class,()->s.current(record,completion));
    }
    @Test void auditFileAuthenticatesRealmEpochAndContentAndRejectsPlainOrTamperedInput() throws Exception {
        var cipher=new ErasureCipher("test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
        Path file=directory.resolve("retirement-audit.bin");byte[] plain=new ObjectMapper().writeValueAsBytes(audit(now,absent));
        Files.write(file,plain);var source=RetirementOperationalEvidence.authenticatedFile(file,"test",id,cipher,new FileErasureObjectStoreTest.TestSafety());
        assertThrows(IllegalStateException.class,source::read);
        byte[] encrypted=cipher.encryptDocument("test","retirement-audit-v1/test/"+id,plain);Files.write(file,encrypted);
        assertEquals(audit(now,absent),source.read());encrypted[encrypted.length-1]^=1;Files.write(file,encrypted);
        assertThrows(IllegalStateException.class,source::read);
    }
}
