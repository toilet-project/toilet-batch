package com.example.toiletbatch.account;

import com.geupddong.account.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountErasureEvidenceCollectorTest {
    private static final Instant NOW=Instant.parse("2026-09-06T12:00:00Z");
    private static final String EPOCH="01234567-1234-1234-1234-123456789012";
    private final ErasureRecord intent=new ErasureRecord(1,"verification",1,"2000-01-01T00:00",
            "01234567-1234-1234-1234-123456789013","2000-04-01T00:00");
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private AccountErasureEvidenceCollector.CompletionStore store;
    private AccountErasureEvidenceCollector collector;
    private final BackupEvidenceInventory backups=new BackupEvidenceInventory(NOW,List.of(),0);
    @BeforeEach void setup() {
        ds=new DriverManagerDataSource("jdbc:h2:mem:evidence"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE app_user (user_id BIGINT PRIMARY KEY, created_at TIMESTAMP NOT NULL)");
        store=mock(AccountErasureEvidenceCollector.CompletionStore.class);
        when(store.writeOnce(any())).thenAnswer(call->call.getArgument(0));
        collector=new AccountErasureEvidenceCollector(jdbc,store,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    private ErasureIntentInventory inventory() { return new ErasureIntentInventory(1,intent.realm(),EPOCH,Map.of(intent.objectKey(),ErasureCompletion.digest(intent))); }
    private void insert() { jdbc.update("INSERT INTO app_user VALUES (1, TIMESTAMP '2000-01-01 00:00:00')"); }
    @Test void dryRunDoesNotWriteR2OrDatabaseAndEmptyDirectoryDoesNotClearRetention() {
        var result=collector.collect(List.of(intent),inventory(),backups,false);
        assertEquals(1,result.absent());assertEquals(1,result.wouldRecord());
        verify(store,never()).writeOnce(any());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class));
        assertFalse(backups.compare(NOW).allCopiesCleared());
    }
    @Test void committedAbsenceCreatesReceiptButUnresolvedBackupsStillBlockRetention() {
        insert();jdbc.update("DELETE FROM app_user WHERE user_id=1");
        var result=collector.collect(List.of(intent),inventory(),backups,true);
        assertEquals(1,result.confirmed());assertEquals(1,result.confirmationsWithUnresolvedBackups());
        verify(store).writeOnce(ErasureCompletion.observed(intent,EPOCH,NOW));
    }
    @Test void rollbackLeavesAccountPendingAndCreatesNoReceipt() {
        insert();new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status->{
            jdbc.update("DELETE FROM app_user WHERE user_id=1");status.setRollbackOnly();
        });
        assertEquals(1,collector.collect(List.of(intent),inventory(),backups,true).pending());
        verifyNoInteractions(store);
    }
    @Test void collectionInsideDeleteTransactionIsForbidden() {
        insert();new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status->{
            jdbc.update("DELETE FROM app_user WHERE user_id=1");
            assertThrows(IllegalStateException.class,()->collector.collect(List.of(intent),inventory(),backups,true));
            status.setRollbackOnly();
        });verifyNoInteractions(store);
    }
    @Test void differentIdentityAbortsBeforeAnyWrites() {
        jdbc.update("INSERT INTO app_user VALUES (1, TIMESTAMP '2001-01-01 00:00:00')");
        assertThrows(IllegalStateException.class,()->collector.collect(List.of(intent),inventory(),backups,true));
        verifyNoInteractions(store);
    }
    @Test void manifestMismatchIsNotSilentlyAccepted() {
        var manifest=new ErasureIntentInventory(1,intent.realm(),EPOCH,Map.of(intent.objectKey(),"0".repeat(64)));
        assertThrows(IllegalStateException.class,()->collector.collect(List.of(intent),manifest,backups,true));
        verifyNoInteractions(store);
    }
    @Test void r2FailureCanRetryEvenThoughDatabaseRowHasAlreadyGone() {
        when(store.writeOnce(any())).thenThrow(new IllegalStateException("unavailable")).thenAnswer(call->call.getArgument(0));
        assertThrows(IllegalStateException.class,()->collector.collect(List.of(intent),inventory(),backups,true));
        assertEquals(1,collector.collect(List.of(intent),inventory(),backups,true).confirmed());
    }
    @Test void existingReceiptIsReadOnlyAndFutureIntentRemainsPending() {
        var first=ErasureCompletion.observed(intent,EPOCH,NOW.minusSeconds(60));when(store.read(any())).thenReturn(first);
        assertEquals(1,collector.collect(List.of(intent),inventory(),backups,false).confirmed());
        verify(store,never()).writeOnce(any());
        reset(store);
        var future=new ErasureRecord(1,intent.realm(),1,intent.userCreatedAt(),intent.withdrawalKey(),"2099-01-01T00:00");
        var manifest=new ErasureIntentInventory(1,future.realm(),EPOCH,Map.of(future.objectKey(),ErasureCompletion.digest(future)));
        assertEquals(1,collector.collect(List.of(future),manifest,backups,true).pending());verifyNoInteractions(store);
    }
    @Test void reappearingAccountDuringR2ReadIsNotAcknowledged() {
        when(store.read(any())).thenAnswer(call->{insert();return null;});
        assertThrows(IllegalStateException.class,()->collector.collect(List.of(intent),inventory(),backups,true));
        verify(store,never()).writeOnce(any());
    }
}
