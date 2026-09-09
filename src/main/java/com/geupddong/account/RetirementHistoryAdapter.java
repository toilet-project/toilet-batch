package com.geupddong.account;

import java.time.Clock;
import com.geupddong.account.GitHubErasureHistoryStore.*;
import com.geupddong.account.RetirementRecoveryJournal.Entry;
import com.geupddong.account.RetirementRecoveryCoordinator.State;

final class RetirementHistoryAdapter implements RetirementRecoveryCoordinator.Independent {
    private final GitHubErasureHistoryStore store;private final Clock clock;
    RetirementHistoryAdapter(GitHubErasureHistoryStore store,Clock clock){this.store=store;this.clock=clock;}
    @Override public State read(){var v=store.inspect();return v.latest().isEmpty()?null:new State(v.head().revision(),v.latest().getLast().binding().journalDigest(),v.latest().getLast().phase());}
    private Binding binding(Entry e) {
        if(e.recovery()==null)throw new IllegalStateException("RETIREMENT_RECOVERY_REQUIRED");
        return new Binding(e.realm(),e.epoch(),e.operationId(),e.checkpointRevision(),e.checkpointDigest(),e.recovery().checkpoint().sequence(),
                e.beforeCount(),e.afterInventoryDigest(),e.digest(),e.planDigest(),e.preparedAt(),e.validUntil());
    }
    @Override public void prepare(Entry entry){store.appendPhase(entry.checkpointRevision(),new Phase(2,0,binding(entry),"",clock.instant().toString()));}
    @Override public void advance(State expected,Entry entry,int nextPhase) {
        var view=store.inspect();if(view.latest().isEmpty())throw new IllegalStateException("RETIREMENT_HISTORY_MISMATCH");
        var previous=view.latest().getLast();
        if(!view.head().revision().equals(expected.revision()) || previous.phase()!=expected.phase()
                || !previous.binding().journalDigest().equals(expected.journalDigest()) || !previous.binding().equals(binding(entry)))
            throw new IllegalStateException("RETIREMENT_HISTORY_MISMATCH");
        store.appendPhase(expected.revision(),new Phase(2,nextPhase,previous.binding(),previous.digest(),clock.instant().toString(),
                view.reviewSequence(),view.authorizedUntil()));
    }
    @Override public String authorizedUntil(State expected,Entry entry) {
        var view=store.inspect();var p=view.latest().getLast();
        if(!view.head().revision().equals(expected.revision()) || p.phase()!=expected.phase() || !p.binding().journalDigest().equals(entry.digest()))
            throw new IllegalStateException("RETIREMENT_HISTORY_MISMATCH");return view.authorizedUntil();
    }
    @Override public void renew(State expected,Entry entry,String evidenceDigest) {
        var view=store.inspect();var p=view.latest().getLast();
        if(!view.head().revision().equals(expected.revision()) || p.phase()!=expected.phase() || !p.binding().equals(binding(entry)))
            throw new IllegalStateException("RETIREMENT_HISTORY_MISMATCH");
        var at=clock.instant();store.appendReview(expected.revision(),new Review(2,p.binding().checkpointSequence(),view.reviewSequence()+1,p.phase(),p.digest(),
                entry.digest(),at.toString(),at.plusSeconds(600).toString(),evidenceDigest,view.reviews().isEmpty()?"":view.reviews().getLast().digest()));
    }
}
