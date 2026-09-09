package com.geupddong.account;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit batch maintenance assembly; no implicit flag changes or unguarded deletion path. */
public final class RetirementMaintenanceRunner {
    public record Result(int completed,int held,boolean resumed) { }
    private RetirementMaintenanceRunner(){}
    public static Result run(Environment env,JdbcTemplate jdbc,boolean apply,boolean reviewedResume) {
        try {
            if(apply && (!env.getProperty("ERASURE_RETIREMENT_WRITE_ENABLED",Boolean.class,false)
                    || !env.getProperty("ERASURE_HISTORY_COMPATIBILITY_VERIFIED",Boolean.class,false)))throw failure();
            if(!"LOCAL".equals(env.getRequiredProperty("ERASURE_LEDGER_PROVIDER")))throw failure();
            String realm=env.getRequiredProperty("ERASURE_LEDGER_REALM"),epoch=env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH");
            Path root=Path.of(env.getRequiredProperty("ERASURE_LEDGER_LOCAL_DIRECTORY"));
            Path journalRoot=Path.of(env.getRequiredProperty("ERASURE_RETIREMENT_JOURNAL_DIRECTORY"));
            Path audit=Path.of(env.getRequiredProperty("ERASURE_RETIREMENT_AUDIT_FILE"));
            Path lock=Path.of(env.getRequiredProperty("ERASURE_MAINTENANCE_DIRECTORY")).resolve(".maintenance.lock");
            if(root.equals(journalRoot) || root.equals(audit.getParent()) || journalRoot.equals(audit.getParent()))throw failure();
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            Map<String,String> keys=mapper.readValue(env.getRequiredProperty("ERASURE_LEDGER_KEYS_JSON"),new TypeReference<>(){});
            var cipher=new ErasureCipher(env.getRequiredProperty("ERASURE_LEDGER_ACTIVE_KEY_ID"),keys);
            var held=new AtomicBoolean();Runnable requireLease=()->{if(!held.get())throw failure();};
            RetirementRecoveryCoordinator.Lease lease=work->{
                try(var acquired=LocalMaintenanceLease.acquire(lock,1000)) {
                    if(!held.compareAndSet(false,true))throw failure();try{work.run();}finally{held.set(false);}
                }
            };
            Clock clock=Clock.systemUTC();
            var history=GitHubErasureHistoryStore.configured(env.getRequiredProperty("ERASURE_CHECKPOINT_GITHUB_TOKEN"));
            var objects=new FileErasureObjectStore(root,realm,env.getRequiredProperty("ERASURE_LEDGER_LOCAL_STORE_ID"));
            var journal=new RetirementRecoveryJournal(journalRoot,realm,env.getRequiredProperty("ERASURE_RETIREMENT_JOURNAL_STORE_ID"),cipher);
            var source=new RetirementOperationalEvidence(jdbc,realm,epoch,env.getRequiredProperty("ERASURE_RETIREMENT_SERVER_UUID"),
                    env.getRequiredProperty("ERASURE_RETIREMENT_SCOPE_SHA256"),
                    RetirementOperationalEvidence.authenticatedFile(audit,realm,epoch,cipher,new FileErasureObjectStore.LinuxSafety()),clock,requireLease);
            var context=new RetirementExecutionContext(objects,cipher,realm,epoch,source,clock,requireLease);
            var service=new RetirementMaintenanceService(journal,history,context,lease,clock);
            int completed=0,blocked=0;boolean resumed=false;
            // Existing work always wins. Never create another job after an ambiguous acknowledgement.
            var view=history.inspect();
            if(view.pending()) {
                if(!apply)return new Result(0,1,true);
                String operation=view.latest().getLast().binding().operationId();
                if(view.latest().getLast().phase()<7) {
                    if(reviewedResume)service.reviewAndResume(operation);else service.resume(operation);
                }
                service.cleanup(operation);completed++;resumed=true;
            } else if(!journal.operations(2).isEmpty()) {
                // A local prepare was never acknowledged, or a cleaned record reappeared: explicit recovery required.
                return new Result(completed,1,resumed);
            }
            int limit=env.getProperty("ERASURE_RETIREMENT_MAX_PER_RUN",Integer.class,10);
            if(limit<1 || limit>100)throw failure();
            var candidates=new ArrayList<String>();
            lease.run(()->{
                context.verifyRemaining(history.read());
                var ledger=new ObjectErasureLedger(objects,cipher,realm,true);
                ledger.intentsAtMost(100000).stream().sorted(Comparator.comparing(ErasureRecord::eligibleAt).thenComparing(ErasureRecord::objectKey))
                        .filter(context::reviewAgeReached).map(ErasureRecord::objectKey).forEach(candidates::add);
            });
            int attempts=0;
            for(String key:candidates) {
                if(completed>=limit || attempts++>=limit)break;
                String operation=UUID.randomUUID().toString();
                // Only read-only candidate preparation errors may become counted holds.
                try {lease.run(()->context.prepare(operation,history.read(),key));}
                catch(RuntimeException notEligible){blocked++;continue;}
                if(!apply){completed++;continue;}
                service.prepare(operation,key);service.resume(operation);service.cleanup(operation);completed++;
            }
            return new Result(completed,blocked,resumed);
        }catch(Exception ignored){throw failure();}
    }
    private static IllegalStateException failure(){return new IllegalStateException("RETIREMENT_MAINTENANCE_HELD");}
}
