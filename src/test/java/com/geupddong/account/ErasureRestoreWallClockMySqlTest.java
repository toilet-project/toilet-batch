package com.geupddong.account;

import com.example.toiletbatch.account.NativeMySqlFixture;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ACCOUNT_RETENTION_MYSQL_MARKER", matches="[a-f0-9]{10}")
class ErasureRestoreWallClockMySqlTest {
    @Test void koreanDatetimeIdentityIsStableInUtcAndKoreanJvm() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC","Asia/Seoul")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var ds = NativeMySqlFixture.create();
                var jdbc = new JdbcTemplate(ds);
                jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY, created_at DATETIME NOT NULL)");
                jdbc.execute("INSERT INTO app_user VALUES(1,'2000-01-01 00:00:00')");
                var expected = LocalDateTime.of(2000,1,1,0,0);
                assertEquals(expected, jdbc.queryForObject("SELECT created_at FROM app_user", LocalDateTime.class));
                if (zone.equals("UTC")) assertNotEquals(expected,
                        jdbc.queryForObject("SELECT created_at FROM app_user", java.sql.Timestamp.class).toLocalDateTime());
                var record = new ErasureRecord(1,"verification",1,expected.toString(),UUID.randomUUID().toString(),"2000-04-01T00:00");
                var result = new AccountErasureRestore(jdbc,new DataSourceTransactionManager(ds)).replay(
                        List.of(record),"verification",LocalDateTime.of(2026,9,6,0,0),false);
                assertEquals(1,result.matched());
                assertEquals(0,result.erased());
            }
        } finally { TimeZone.setDefault(previous); }
    }
}
