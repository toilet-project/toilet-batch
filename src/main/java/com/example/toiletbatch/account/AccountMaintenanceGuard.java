package com.example.toiletbatch.account;

import com.geupddong.account.LocalMaintenanceLease;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fail closed until the shared lock has been provisioned and every participant deployed. */
@Component
public class AccountMaintenanceGuard {
    @FunctionalInterface public interface Lease extends AutoCloseable { @Override void close(); }
    private final Environment env;
    public AccountMaintenanceGuard(Environment env) {this.env=env;}
    public Lease acquire() {
        if(!"LOCAL".equals(env.getProperty("ERASURE_LEDGER_PROVIDER"))
                || !env.getProperty("ERASURE_MAINTENANCE_LOCK_ENABLED",Boolean.class,false)
                || !"/home/luha/geupddong-maintenance".equals(env.getProperty("ERASURE_MAINTENANCE_DIRECTORY"))
                || !"/home/luha/geupddong-erasure-ledger".equals(env.getProperty("ERASURE_LEDGER_LOCAL_DIRECTORY")))
            throw new IllegalStateException("ACCOUNT_MAINTENANCE_GUARD_NOT_READY");
        var lease=LocalMaintenanceLease.acquire(Path.of("/home/luha/geupddong-maintenance/.maintenance.lock"),1000);
        return lease::close;
    }
}
