package com.geupddong.account;

import java.util.Map;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class ErasureRestoreEnvironmentTest {
    @Test void uppercaseProcessEnvironmentMapsToStandaloneCliProperties() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("synthetic-env", Map.of(
                "ERASURE_LEDGER_REALM", "verification",
                "ERASURE_LEDGER_BUCKET", "synthetic-test",
                "ERASURE_LEDGER_ENDPOINT", "https://00000000000000000000000000000000.r2.cloudflarestorage.com",
                "ERASURE_LEDGER_ACCESS_KEY_ID", "synthetic",
                "ERASURE_LEDGER_SECRET_ACCESS_KEY", "synthetic",
                "ERASURE_LEDGER_ACTIVE_KEY_ID", "k1",
                "ERASURE_LEDGER_KEYS_JSON", "{\"k1\":\""+Base64.getEncoder().encodeToString(new byte[32])+"\"}")));
        assertDoesNotThrow(() -> { try (var ledger = ErasureLedgerFactory.configured(env)) { assertNotNull(ledger); } });
    }
}
