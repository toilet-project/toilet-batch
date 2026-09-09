package com.example.toiletbatch.account;

import com.geupddong.account.RetirementMaintenanceRunner;
import com.example.toiletbatch.batch.BatchFailureNotifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;

@Service
public class AccountLedgerRetirementJob {
    private final Environment env;private final JdbcTemplate jdbc;private final BatchFailureNotifier notifier;
    public AccountLedgerRetirementJob(Environment env,JdbcTemplate jdbc,BatchFailureNotifier notifier){this.env=env;this.jdbc=jdbc;this.notifier=notifier;}
    public void runAfterCompletion() {
        if(!env.getProperty("ERASURE_RETIREMENT_WRITE_ENABLED",Boolean.class,false)
                || env.getProperty("account.lifecycle.maintenance",Boolean.class,true))return;
        try {
            var result=RetirementMaintenanceRunner.run(env,jdbc,true,false);
            LoggerFactory.getLogger(getClass()).info("Ledger retirement finished: completed={}, held={}, resumed={}",result.completed(),result.held(),result.resumed());
            if(result.held()>0)notifier.notifyAccountErasureFailure(result.held(),0,true);
        }catch(Exception ignored){
            LoggerFactory.getLogger(getClass()).error("Ledger retirement held (RETIREMENT_REVIEW_REQUIRED)");
            try{notifier.notifyAccountErasureFailure(1,0,true);}catch(RuntimeException suppressed){LoggerFactory.getLogger(getClass()).error("Retirement notice unavailable");}
        }
    }
}
