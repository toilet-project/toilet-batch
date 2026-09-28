package com.example.toiletbatch.mobile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobileCatalogTest {
    @TempDir Path directory;

    @Test void disabledPublicationNeverReadsDatabaseOrStartsPython() throws Exception {
        var source=mock(DataSource.class);
        var properties=new MobileCatalogProperties(false,null,"secret",null,null,null,0);
        assertEquals("disabled",new MobileCatalogPublisher(source,properties).publish(false,List.of()));
        verifyNoInteractions(source);
        assertFalse(properties.toString().contains("secret"));
    }

    @Test void exportUsesOneReadOnlySnapshotAndPublicQueriesOnly() throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        var queries=new java.util.ArrayList<String>();var counter=new AtomicInteger();
        when(connection.createStatement()).thenAnswer(call->{
            var statement=mock(Statement.class);var rows=mock(ResultSet.class);
            when(rows.next()).thenReturn(true,false);
            when(rows.getString(1)).thenReturn("{\"public\":"+counter.incrementAndGet()+"}");
            when(statement.executeQuery(anyString())).thenAnswer(q->{queries.add(q.getArgument(0));return rows;});
            return statement;
        });
        var file=directory.resolve("public.jsonl");new MobileCatalogExporter(source).export(file);
        assertEquals(7,queries.size());assertEquals(7,Files.readAllLines(file).size());
        assertTrue(queries.stream().allMatch(q->q.startsWith("SELECT ")));
        assertTrue(queries.stream().noneMatch(q->q.matches("(?is).*\\b(users|member|review|password|refresh_token)\\b.*")));
        assertTrue(queries.stream().anyMatch(q->q.contains("'zh-tw'")&&q.contains("'zh-hk'")));
        var order=inOrder(connection);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        order.verify(connection).setReadOnly(true);order.verify(connection).setAutoCommit(false);
        order.verify(connection,times(7)).createStatement();order.verify(connection).rollback();order.verify(connection).close();
        verify(connection,never()).commit();
    }

    @Test void failedExportRollsBackAndClosesConnection() throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("synthetic failure"));
        assertThrows(SQLException.class,()->new MobileCatalogExporter(source).export(directory.resolve("failed.jsonl")));
        verify(connection).rollback();verify(connection).close();verify(connection,never()).commit();
    }

    @Test void invalidOriginOrBaselineIsRejectedBeforeDatabaseAccess() {
        var source=mock(DataSource.class);
        var badOrigin=new MobileCatalogProperties(true,"https://other.example","secret",null,null,null,0);
        assertThrows(IllegalStateException.class,()->new MobileCatalogPublisher(source,badOrigin).publish(false,List.of()));
        var validOrigin=new MobileCatalogProperties(true,null,"secret",null,null,null,0);
        assertThrows(IllegalArgumentException.class,()->new MobileCatalogPublisher(source,validOrigin).publish(false,List.of("../private")));
        verifyNoInteractions(source);
    }

    @Test void schedulerRunsOnlyThisPublicationAndContainsFailures() throws Exception {
        var publisher=mock(MobileCatalogPublisher.class);when(publisher.publish(false,List.of())).thenThrow(new IllegalStateException("secret"));
        assertDoesNotThrow(()->new MobileCatalogScheduler(publisher).publishDaily());
        verify(publisher).publish(false,List.of());verifyNoMoreInteractions(publisher);
    }

    @Test void cliCanCheckRuntimeWithoutBootingApplicationOrRequiringCredentials() throws Exception {
        MobileCatalogCli.main(new String[]{"--check-runtime"});
        assertThrows(IllegalArgumentException.class,()->MobileCatalogCli.main(new String[]{"--unknown"}));
    }
}
