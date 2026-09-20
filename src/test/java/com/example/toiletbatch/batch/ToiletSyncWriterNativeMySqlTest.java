package com.example.toiletbatch.batch;

import com.example.toiletbatch.account.NativeMySqlFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ACCOUNT_RETENTION_MYSQL_MARKER", matches = "[a-f0-9]{10}")
class ToiletSyncWriterNativeMySqlTest {
    @Test void syncPreservesConfirmedCoordinatesAndUnchangedTranslationTimestamp() {
        new ToiletSyncWriterTest().verifyWriter(NativeMySqlFixture.create(), false);
    }
}
