package com.geupddong.account;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.account.RetirementRecoveryJournal.*;
import com.geupddong.account.GitHubRetirementRecoveryStore.Phase;

/** In-memory Git object graph and fast-forward reference semantics; no live repository writes. */
class GitHubRetirementRecoveryStoreTest {
    @TempDir Path directory;
    static final String ID="00000000-0000-0000-0000-000000000001",JOB="00000000-0000-0000-0000-000000000099";
    static final Instant NOW=Instant.parse("2026-10-10T00:00:00Z");
    final ObjectMapper json=new ObjectMapper();
    final ErasureCheckpoint cp=new ErasureCheckpoint(1,"test",ID,1,2,"b".repeat(64),"",NOW.minusSeconds(60).toString());
    record Commit(String tree,String parent) { }
    class Git implements GitHubErasureCheckpointStore.Transport {
        final Map<String,byte[]> blobs=new HashMap<>();
        final Map<String,Map<String,String>> trees=new HashMap<>();
        final Map<String,Commit> commits=new HashMap<>();
        final List<String> calls=new ArrayList<>();
        String revision="a".repeat(40);int sequence=100;
        boolean conflict,loseAck,truncated,offline; String wrongMode;
        Git() {
            String blob=id();blobs.put(blob,cp.bytes());String readme=id();blobs.put(readme,"private repository".getBytes());
            String tree=id();trees.put(tree,Map.of("checkpoints/checkpoint-00000000000000000001.json",blob,"README.md",readme));
            commits.put(revision,new Commit(tree,null));
        }
        String id() {return String.format(Locale.ROOT,"%040d",++sequence);}
        Map<String,String> files() {return trees.get(commits.get(revision).tree());}
        void replace(String path,byte[] bytes) {
            String blob=id();blobs.put(blob,bytes); var tree=new TreeMap<>(files());tree.put(path,blob);
            String treeId=id();trees.put(treeId,tree);String next=id();commits.put(next,new Commit(treeId,revision));revision=next;
        }
        void remove(String path) {
            var tree=new TreeMap<>(files());tree.remove(path);String treeId=id();trees.put(treeId,tree);
            String next=id();commits.put(next,new Commit(treeId,revision));revision=next;
        }
        public JsonNode request(String method,String path,Object body) {
            calls.add(method+" "+path);if(offline) throw new IllegalStateException("private network failure");
            JsonNode value=json.valueToTree(body);
            if(path.equals("/git/ref/heads/main")) return json.valueToTree(Map.of("object",Map.of("sha",revision)));
            if(method.equals("GET") && path.startsWith("/git/trees/")) {
                String key=path.substring(11).split("\\?")[0];
                var files=trees.get(commits.containsKey(key)?commits.get(key).tree():key);var items=new ArrayList<Object>();
                files.forEach((name,blob)->items.add(Map.of("path",name,"type","blob","mode",
                        name.contains("retirement-") && wrongMode!=null?wrongMode:"100644","sha",blob)));
                return json.valueToTree(Map.of("truncated",truncated,"tree",items));
            }
            if(method.equals("GET") && path.startsWith("/git/blobs/")) {
                byte[] bytes=blobs.get(path.substring(11));
                return json.valueToTree(Map.of("encoding","base64","size",bytes.length,"content",Base64.getEncoder().encodeToString(bytes)));
            }
            if(method.equals("GET") && path.startsWith("/git/commits/"))
                return json.valueToTree(Map.of("tree",Map.of("sha",commits.get(path.substring(13)).tree())));
            if(method.equals("POST") && path.equals("/git/blobs")) {
                String id=id();blobs.put(id,Base64.getDecoder().decode(value.path("content").asText()));return json.valueToTree(Map.of("sha",id));
            }
            if(method.equals("POST") && path.equals("/git/trees")) {
                var tree=new TreeMap<>(trees.get(value.path("base_tree").asText()));assertTrue(value.path("tree").size()>=1 && value.path("tree").size()<=2);
                for(var item:value.path("tree")) {
                    assertEquals("100644",item.path("mode").asText());
                    assertFalse(tree.containsKey(item.path("path").asText()),"never replace an acknowledged phase");
                    tree.put(item.path("path").asText(),item.path("sha").asText());
                }
                String id=id();trees.put(id,tree);return json.valueToTree(Map.of("sha",id));
            }
            if(method.equals("POST") && path.equals("/git/commits")) {
                assertEquals(1,value.path("parents").size());String id=id();
                commits.put(id,new Commit(value.path("tree").asText(),value.path("parents").get(0).asText()));
                return json.valueToTree(Map.of("sha",id));
            }
            if(method.equals("PATCH") && path.equals("/git/refs/heads/main")) {
                assertFalse(value.path("force").asBoolean(true));String next=value.path("sha").asText();
                if(conflict || !Objects.equals(commits.get(next).parent(),revision)) throw new IllegalStateException("non-fast-forward");
                revision=next;if(loseAck) throw new IllegalStateException("lost ack");return json.createObjectNode();
            }
            throw new AssertionError(method+" "+path);
        }
    }
    Entry entry() {
        return new Entry(1,"test",ID,JOB,NOW.toString(),NOW.plusSeconds(600).toString(),cp.digest(),"a".repeat(40),
                "c".repeat(64),2,"d".repeat(64),"e".repeat(64),List.of(
                new Target("v1/test/"+ID+".bin","1".repeat(64)),new Target("catalogue-v1/test/"+ID+".bin","2".repeat(64)),
                new Target("completion-v1/test/"+ID+"/"+ID+".bin","3".repeat(64))));
    }
    GitHubRetirementRecoveryStore store(Git git) {return new GitHubRetirementRecoveryStore(git,"test",ID,Clock.fixed(NOW,ZoneOffset.UTC));}
    void rejected(Runnable work) {
        var e=assertThrows(IllegalStateException.class,work::run);assertEquals("RETIREMENT_GITHUB_UNAVAILABLE",e.getMessage());assertNull(e.getCause());
    }
    Phase phase(Git git,int number) throws Exception {
        return json.readValue(git.blobs.get(git.files().get("checkpoints/retirement-phase-"+number+".json")),Phase.class);
    }
    @Test void beforePreparationReadsExistingBaselineWithoutWriting() {
        var git=new Git();assertNull(store(git).read());assertTrue(git.calls.stream().allMatch(c->c.startsWith("GET ")));
    }
    @Test void appendAllPhasesPreservesLegacyCheckpointAndOnlyCommitsFinalInventoryAtSeven() {
        var git=new Git();var s=store(git);var before=Map.copyOf(git.files());s.prepare(entry());
        assertEquals(0,s.read().phase());rejected(s::committedInventory);
        for(int phase=1;phase<=7;phase++) {s.advance(s.read(),entry(),phase);assertEquals(phase,store(git).read().phase());}
        assertEquals(1,s.committedInventory().count());assertEquals(entry().afterInventoryDigest(),s.committedInventory().inventorySha256());
        before.forEach((k,v)->assertEquals(v,git.files().get(k)));assertEquals(before.size()+8,git.files().size());
        rejected(()->s.prepare(entry())); // No silent second job or removal of the barrier.
    }
    @Test void unchangedV1ReaderBlocksAtPreparationAndCompletion() {
        var git=new Git();var s=store(git);var legacy=new GitHubErasureCheckpointStore(git);
        assertEquals(cp,legacy.read().checkpoint());s.prepare(entry());assertThrows(IllegalStateException.class,legacy::read);
        for(int p=1;p<=7;p++) s.advance(s.read(),entry(),p);
        assertThrows(IllegalStateException.class,legacy::read);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7})
    void lostAckCanBeReadBackWithoutInventingOrRepeatingPhase(int target) {
        var git=new Git();var s=store(git);
        if(target>0) {s.prepare(entry());for(int p=1;p<target;p++)s.advance(s.read(),entry(),p);}
        var expected=s.read();git.loseAck=true;
        rejected(()->{if(target==0)s.prepare(entry());else s.advance(expected,entry(),target);});
        git.loseAck=false;assertEquals(target,store(git).read().phase());
    }
    @Test void concurrentBranchChangesCannotBeOverwritten() {
        var git=new Git();var s=store(git);s.prepare(entry());var expected=s.read();
        git.replace("README.md","another commit".getBytes());int calls=git.calls.size();
        rejected(()->s.advance(expected,entry(),1));assertTrue(git.calls.subList(calls,git.calls.size()).stream().allMatch(c->c.startsWith("GET ")));
        assertEquals(0,s.read().phase());
    }
    @Test void nonFastForwardNeverForcesOrRetriesMutation() {
        var git=new Git();var s=store(git);git.conflict=true;
        rejected(()->s.prepare(entry()));assertNull(s.read());assertEquals(1,git.calls.stream().filter(c->c.startsWith("PATCH ")).count());
    }
    @Test void missingIntermediatePhaseAndHashChainTamperingFailClosed() throws Exception {
        var git=new Git();var s=store(git);s.prepare(entry());s.advance(s.read(),entry(),1);s.advance(s.read(),entry(),2);
        byte[] original=phase(git,1).bytes();git.remove("checkpoints/retirement-phase-1.json");rejected(s::read);
        git.replace("checkpoints/retirement-phase-1.json",original);Phase p=phase(git,2);
        git.replace("checkpoints/retirement-phase-2.json",new Phase(1,2,p.binding(),"f".repeat(64),p.recordedAt()).bytes());rejected(s::read);
    }
    @Test void malformedUnknownAndWrongModePhaseFilesDoNotBecomeNoActiveWork() {
        var git=new Git();var s=store(git);git.replace("checkpoints/retirement-phase-8.json","{}".getBytes());rejected(s::read);
        git=new Git();s=store(git);s.prepare(entry());git.wrongMode="120000";rejected(s::read);
        git.wrongMode=null;git.replace("checkpoints/retirement-phase-0.json","{}".getBytes());rejected(s::read);
    }
    @Test void offlineOrTruncatedResultNeverInventsAnEmptyState() {
        var git=new Git();var s=store(git);git.offline=true;rejected(s::read);git.offline=false;git.truncated=true;rejected(s::read);
    }
    @Test void changedBaseCheckpointBlocksEvenWhenLegacyIncrementWouldBeValid() {
        var git=new Git();var s=store(git);s.prepare(entry());
        var next=new ErasureCheckpoint(1,"test",ID,2,3,"f".repeat(64),cp.digest(),NOW.toString());
        git.replace("checkpoints/checkpoint-00000000000000000002.json",next.bytes());rejected(s::read);
    }
    @Test void expiryClockRollbackAndWrongRealmBlockMutation() {
        var git=new Git();var s=store(git);s.prepare(entry());var expected=s.read();
        var expired=new GitHubRetirementRecoveryStore(git,"test",ID,Clock.fixed(NOW.plusSeconds(601),ZoneOffset.UTC));
        rejected(()->expired.advance(expected,entry(),1));
        rejected(()->new GitHubRetirementRecoveryStore(git,"test",ID,Clock.fixed(NOW.minusSeconds(1),ZoneOffset.UTC)).read());
        rejected(()->new GitHubRetirementRecoveryStore(git,"other",ID,Clock.fixed(NOW,ZoneOffset.UTC)).read());
    }
    @Test void journalChangeIsRejectedAndAggregateFilesExcludeMemberKeys() {
        var git=new Git();var s=store(git);s.prepare(entry());var original=entry();
        var changed=new Entry(1,"test",ID,JOB,original.preparedAt(),original.validUntil(),original.checkpointDigest(),
                original.checkpointRevision(),"a".repeat(64),2,original.afterInventoryDigest(),original.retainedObjectsDigest(),original.targets());
        var expected=s.read();rejected(()->s.advance(expected,changed,1));
        git.files().forEach((name,sha)->{if(name.contains("retirement-")) {
            String value=new String(git.blobs.get(sha),java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(value.contains("v1/test/"));assertFalse(value.contains("ciphertextSha256"));assertFalse(value.contains(JOB));
        }});
    }
    @Test void fileRecoveryCoordinatorResumesThroughGitAdapterAfterLostArmAcknowledgement() throws Exception {
        var fixture=new RetirementRecoveryTest();fixture.directory=directory;fixture.setup();
        var local=fixture.entry;
        var job=new Entry(1,"test",ID,JOB,local.preparedAt(),local.validUntil(),cp.digest(),"a".repeat(40),
                local.planDigest(),2,local.afterInventoryDigest(),local.retainedObjectsDigest(),local.targets());
        var git=new Git();
        var objects=new RetirementRecoveryCoordinator.Objects() {
            public Map<String,String> inventory() {assertTrue(fixture.held.get());return fixture.hashes();}
            public void removeExact(String key,String hash,boolean retry) {
                assertTrue(fixture.held.get());fixture.deleteCalls++;fixture.objects.removeExact(key,hash,retry);
            }
        };
        RetirementRecoveryCoordinator.Lease lease=work->{
            assertTrue(fixture.held.compareAndSet(false,true));try {work.run();} finally {fixture.held.set(false);}
        };
        RetirementRecoveryCoordinator.Context context=(e,s)->assertTrue(fixture.held.get());
        var first=new RetirementRecoveryCoordinator(fixture.journal(),store(git),objects,context,lease,Clock.fixed(NOW,ZoneOffset.UTC));
        first.prepare(job);git.loseAck=true;
        assertThrows(IllegalStateException.class,()->first.resume(JOB));assertEquals(0,fixture.deleteCalls);
        git.loseAck=false;
        var resumed=new RetirementRecoveryCoordinator(fixture.journal(),store(git),objects,context,lease,Clock.fixed(NOW,ZoneOffset.UTC));
        resumed.resume(JOB);assertEquals(3,fixture.deleteCalls);assertEquals(1,store(git).committedInventory().count());
        assertThrows(IllegalStateException.class,()->new GitHubErasureCheckpointStore(git).read());
    }
}
