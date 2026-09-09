package com.geupddong.account;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalLedgerRedeploymentPreflightTest {
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    @Test void acceptsCanonicalKeyring() throws Exception {
        assertNotNull(LocalLedgerRedeploymentPreflight.cipher("{\"k1\":\""+key+"\"}","k1"));
    }
    @Test void rejectsDuplicateNonStringMissingNoncanonicalAndOversizeKeys() {
        for(String value:new String[]{"{\"k1\":\""+key+"\",\"k1\":\""+key+"\"}",
                "{\"k1\":12}","{}","[]","{\"k1\":\""+key+"\"} {}","{\"k1\":\""+key.replace("=","")+"\"}"," ".repeat(8193)})
            assertThrows(Exception.class,()->LocalLedgerRedeploymentPreflight.cipher(value,"k1"));
    }
}
