package com.example.toiletbatch.account;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class AccountMaintenanceGuardTest {
    @Test void missingConfigurationFailsClosed(){
        assertThrows(IllegalStateException.class,()->new AccountMaintenanceGuard(new MockEnvironment()).acquire());
    }
    @Test void activationOrPathCannotBeOmitted(){
        for(String key:new String[]{"ERASURE_LEDGER_PROVIDER","ERASURE_MAINTENANCE_LOCK_ENABLED",
                "ERASURE_MAINTENANCE_DIRECTORY","ERASURE_LEDGER_LOCAL_DIRECTORY"}){
            var env=new MockEnvironment().withProperty("ERASURE_LEDGER_PROVIDER","LOCAL")
                .withProperty("ERASURE_MAINTENANCE_LOCK_ENABLED","true")
                .withProperty("ERASURE_MAINTENANCE_DIRECTORY","/home/luha/geupddong-maintenance")
                .withProperty("ERASURE_LEDGER_LOCAL_DIRECTORY","/home/luha/geupddong-erasure-ledger");
            env.setProperty(key,key.endsWith("ENABLED")?"false":"wrong");
            assertEquals("ACCOUNT_MAINTENANCE_GUARD_NOT_READY",assertThrows(IllegalStateException.class,
                ()->new AccountMaintenanceGuard(env).acquire()).getMessage());
        }
    }
}
