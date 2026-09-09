package com.geupddong.account;

import java.time.Clock;
import java.util.List;
import com.geupddong.account.GitHubErasureHistoryStore.Phase;

/** No Spring scheduling. Explicit maintenance entry point assembled by the guarded CLI. */
final class RetirementMaintenanceService {
    private final RetirementRecoveryJournal journal;private final GitHubErasureHistoryStore history;
    private final RetirementExecutionContext context;private final RetirementRecoveryCoordinator.Lease lease;private final Clock clock;
    RetirementMaintenanceService(RetirementRecoveryJournal journal,GitHubErasureHistoryStore history,RetirementExecutionContext context,
                                 RetirementRecoveryCoordinator.Lease lease,Clock clock) {
        this.journal=journal;this.history=history;this.context=context;this.lease=lease;this.clock=clock;
    }
    private RetirementRecoveryCoordinator coordinator(){return new RetirementRecoveryCoordinator(journal,new RetirementHistoryAdapter(history,clock),context,context,lease,clock);}
    void prepare(String operation,String intentKey){coordinator().prepare(()->context.prepare(operation,history.read(),intentKey));}
    void resume(String operation){coordinator().resume(operation);}
    void reviewAndResume(String operation){coordinator().review(operation);coordinator().resume(operation);}
    /** Explicit recovery only: discard an unacknowledged local prepare, never ledger objects.
     * A changed independent revision or any missing/changed original object blocks this path. */
    void discardUnacknowledgedPrepare(String operation) {
        lease.run(()->{
            var view=history.inspect();
            if(view.pending() || !journal.operations(2).equals(List.of(operation)))throw failure();
            var entry=journal.read(operation);
            if(entry.recovery()==null || !view.head().revision().equals(entry.checkpointRevision())
                    || !view.head().checkpoint().digest().equals(entry.checkpointDigest()))throw failure();
            context.verifyRemaining(view.head());
            var all=new java.util.TreeMap<>(context.inventory());
            for(var target:entry.targets())
                if(!target.ciphertextSha256().equals(all.remove(target.key())))throw failure();
            if(!RetirementRecoveryCoordinator.inventoryDigest(all).equals(entry.retainedObjectsDigest()))throw failure();
            if(!history.inspect().head().equals(view.head()))throw failure();
            journal.removeCompleted(entry);journal.confirmEmpty();
        });
    }
    void cleanup(String operation) {
        try {lease.run(()->{
            var view=history.inspect();if(view.latest().isEmpty())throw failure();var previous=view.latest().getLast();
            if(previous.phase()<7 || !previous.binding().operationId().equals(operation))throw failure();
            context.verifyRemaining(view.head());var jobs=journal.operations(2);
            if(previous.phase()==8) {if(!jobs.isEmpty())throw failure();journal.confirmEmpty();return;}
            if(!jobs.isEmpty()) {
                if(!jobs.equals(List.of(operation)))throw failure();var entry=journal.read(operation);
                if(!entry.digest().equals(previous.binding().journalDigest()))throw failure();
                journal.removeCompleted(entry);
            }
            // Covers delete followed by crash/fsync failure; absence must be durably re-acknowledged.
            journal.confirmEmpty();context.verifyRemaining(history.inspect().head());
            history.appendPhase(view.head().revision(),new Phase(2,8,previous.binding(),previous.digest(),clock.instant().toString(),view.reviewSequence(),view.authorizedUntil()));
        });}catch(RuntimeException ignored){throw failure();}
    }
    private static IllegalStateException failure(){return new IllegalStateException("RETIREMENT_CLEANUP_BLOCKED");}
}
