package com.example.toiletbatch.account;

import com.geupddong.account.*;
import com.example.toiletbatch.batch.BatchFailureNotifier;
import java.time.Clock;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Post-commit reconciliation, including a previous day's deletion whose receipt write was interrupted. */
@Service
public class AccountErasureCompletionJob {
    private final Environment env;private final JdbcTemplate jdbc;private final AccountMaintenanceGuard guard;private final BatchFailureNotifier notifier;
    public AccountErasureCompletionJob(Environment env,JdbcTemplate jdbc,AccountMaintenanceGuard guard,BatchFailureNotifier notifier) {
        this.env=env;this.jdbc=jdbc;this.guard=guard;this.notifier=notifier;
    }
    public void runAfterErasure() {
        if(!env.getProperty("batch.account-erasure.enabled",Boolean.class,false)
                || env.getProperty("account.lifecycle.maintenance",Boolean.class,true))return;
        try(var lease=guard.acquire();var ledger=ErasureLedgerFactory.configured(env)) {
            String realm=env.getRequiredProperty("erasure.ledger.realm"),epoch=env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH");
            var checkpoints=GitHubErasureHistoryStore.configured(env.getRequiredProperty("ERASURE_CHECKPOINT_GITHUB_TOKEN"));
            var head=checkpoints.read();var cp=head.checkpoint();
            if(!realm.equals(cp.realm()) || !epoch.equals(cp.databaseEpoch()) || cp.count()>5000)throw new IllegalStateException();
            var records=VerifiedErasureSnapshot.read(ledger,checkpoints,realm,epoch,Clock.systemUTC());
            var digests=new TreeMap<String,String>();records.forEach(r->digests.put(r.objectKey(),ErasureCompletion.digest(r)));
            var inventory=new ErasureIntentInventory(1,realm,epoch,digests);
            var completions=new AccountErasureEvidenceCollector.CompletionStore() {
                public ErasureCompletion read(ErasureCompletion e){return ledger.readCompletion(e);}
                public ErasureCompletion writeOnce(ErasureCompletion e){return ledger.ensureCompletion(e);}
            };
            var result=new AccountErasureEvidenceCollector(jdbc,completions,Clock.systemUTC()).collect(records,inventory,
                    new BackupEvidenceInventory(java.time.Instant.now(),List.of(),1),true);
            if(!head.equals(checkpoints.read()))throw new IllegalStateException();
            LoggerFactory.getLogger(getClass()).info("Erasure completion reconciliation: records={}, absent={}, confirmed={}; copyClearance=false",
                    result.records(),result.absent(),result.confirmed());
        }catch(Exception ignored){
            LoggerFactory.getLogger(getClass()).error("Erasure completion reconciliation held (EVIDENCE_RETRY_REQUIRED)");
            try{notifier.notifyAccountErasureFailure(1,0,true);}catch(RuntimeException suppressed){LoggerFactory.getLogger(getClass()).error("Erasure evidence notification unavailable");}
        }
    }
}
