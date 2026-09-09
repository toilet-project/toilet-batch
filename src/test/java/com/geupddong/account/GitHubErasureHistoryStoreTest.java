package com.geupddong.account;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.account.GitHubErasureHistoryStore.*;

class GitHubErasureHistoryStoreTest {
    final GitHubRetirementRecoveryStoreTest f=new GitHubRetirementRecoveryStoreTest();
    final GitHubRetirementRecoveryStoreTest.Git git=f.new Git();
    final Instant now=GitHubRetirementRecoveryStoreTest.NOW;
    GitHubErasureHistoryStore store(){return new GitHubErasureHistoryStore(git,Clock.fixed(now,ZoneOffset.UTC));}
    Binding start() {
        var head=store().read();var cp=head.checkpoint();
        var b=new Binding(cp.realm(),cp.databaseEpoch(),new UUID(0,cp.sequence()+99).toString(),head.revision(),cp.digest(),cp.sequence(),cp.count(),
                "d".repeat(64),"e".repeat(64),"f".repeat(64),now.toString(),now.plusSeconds(600).toString());
        store().appendPhase(head.revision(),new Phase(2,0,b,"",now.toString()));return b;
    }
    void advance(int through) {
        while(store().inspect().latest().getLast().phase()<through) {
            var view=store().inspect();var p=view.latest().getLast();
            store().appendPhase(view.head().revision(),new Phase(2,p.phase()+1,p.binding(),p.digest(),now.toString()));
        }
    }
    @Test void canAppendRetireCleanupAppendAndRetireAgain() {
        var initial=store().read();start();advance(7);
        assertEquals(1,store().inspect().head().checkpoint().count());assertThrows(IllegalStateException.class,()->store().read());
        advance(8);var completed=store().read();assertEquals(2,completed.checkpoint().sequence());
        var cp=completed.checkpoint();var plus=new ErasureCheckpoint(1,cp.realm(),cp.databaseEpoch(),cp.sequence()+1,2,"a".repeat(64),cp.digest(),now.toString());
        store().append(completed,plus);start();advance(8);assertEquals(1,store().read().checkpoint().count());
        assertEquals(4,store().read().checkpoint().sequence());
        assertThrows(IllegalStateException.class,()->new GitHubErasureCheckpointStore(git).read());
        assertTrue(git.files().containsKey(GitHubErasureHistoryStore.checkpointPath(initial.checkpoint().sequence())));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7})
    void everyUncleanedPhaseBlocksNormalReadersAndWriters(int phase) {
        var head=store().read();start();advance(phase);
        assertThrows(IllegalStateException.class,()->store().read());
        var cp=head.checkpoint();var next=new ErasureCheckpoint(1,cp.realm(),cp.databaseEpoch(),2,3,"a".repeat(64),cp.digest(),now.toString());
        int before=git.calls.size();assertThrows(IllegalStateException.class,()->store().append(head,next));
        assertTrue(git.calls.subList(before,git.calls.size()).stream().allMatch(c->c.startsWith("GET ")));
    }
    @Test void commitAndDecreasedCheckpointAreOneGitCommitEvenWithLostAcknowledgement() {
        start();advance(6);var view=store().inspect();var previous=view.latest().getLast();git.loseAck=true;
        assertThrows(IllegalStateException.class,()->store().appendPhase(view.head().revision(),new Phase(2,7,previous.binding(),previous.digest(),now.toString())));
        git.loseAck=false;assertEquals(7,store().inspect().latest().getLast().phase());
        assertEquals(1,store().inspect().head().checkpoint().count());advance(8);assertEquals(1,store().read().checkpoint().count());
    }
    @Test void unprovedDecreaseMissingCommitOrMissingCheckpointAreRejected() {
        var cp=store().read().checkpoint();var lower=new ErasureCheckpoint(1,cp.realm(),cp.databaseEpoch(),2,1,"a".repeat(64),cp.digest(),now.toString());
        git.replace(GitHubErasureHistoryStore.checkpointPath(2),lower.bytes());assertThrows(IllegalStateException.class,()->store().inspect());
    }
    @Test void deletingAnyRetirementProofCannotMakeADecreaseValid() {
        start();advance(8);git.remove(GitHubErasureHistoryStore.phasePath(1,3));assertThrows(IllegalStateException.class,()->store().read());
    }
    @Test void committedPhaseWithoutAtomicCheckpointIsInvalid() {
        start();advance(7);git.remove(GitHubErasureHistoryStore.checkpointPath(2));assertThrows(IllegalStateException.class,()->store().inspect());
    }
    @Test void cleanupCanAcknowledgeAfterExpiryButNoNewDeletionCan() {
        start();advance(7);var view=store().inspect();var p=view.latest().getLast();
        var later=new GitHubErasureHistoryStore(git,Clock.fixed(now.plusSeconds(3600),ZoneOffset.UTC));
        later.appendPhase(view.head().revision(),new Phase(2,8,p.binding(),p.digest(),now.plusSeconds(3600).toString()));
        assertEquals(1,later.read().checkpoint().count());
    }
    @Test void wrongPhaseParentAndConcurrentRevisionDoNotWrite() {
        start();var view=store().inspect();var p=view.latest().getLast();
        assertThrows(IllegalStateException.class,()->store().appendPhase(view.head().revision(),new Phase(2,1,p.binding(),"a".repeat(64),now.toString())));
        git.replace("README.md","concurrent".getBytes());
        assertThrows(IllegalStateException.class,()->store().appendPhase(view.head().revision(),new Phase(2,1,p.binding(),p.digest(),now.toString())));
        assertEquals(0,store().inspect().latest().getLast().phase());
    }
    @Test void immutableBlobCacheNeverCachesAuthoritativeBranchState() {
        var cached=store();cached.read();long first=git.calls.stream().filter(c->c.startsWith("GET /git/blobs/")).count();
        cached.read();assertEquals(first,git.calls.stream().filter(c->c.startsWith("GET /git/blobs/")).count());
        start();assertThrows(IllegalStateException.class,cached::read);
        git.offline=true;assertThrows(IllegalStateException.class,cached::read);
    }
}
