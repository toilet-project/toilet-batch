package com.example.toiletbatch.account;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountErasureRestoreTargetTest {
    @Test void onlyLiteralPrivateIpv4CanBeAnIsolatedContainerTarget() {
        for (String host : new String[]{"10.0.0.2","172.16.0.2","172.31.255.254","192.168.1.2"})
            assertTrue(AccountErasureRestoreCli.isPrivateIpv4(host));
        for (String host : new String[]{"8.8.8.8","127.0.0.1","172.15.0.2","172.32.0.2","192.169.0.2","10.0.0.256","010.0.0.2","localhost","example.com","::1","169.254.169.254","10.0.0.2/path"})
            assertFalse(AccountErasureRestoreCli.isPrivateIpv4(host));
    }
}
