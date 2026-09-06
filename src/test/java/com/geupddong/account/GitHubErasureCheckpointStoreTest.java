package com.geupddong.account;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GitHubErasureCheckpointStoreTest {
    final ObjectMapper json=new ObjectMapper();
    final ErasureCheckpoint first=CheckpointedErasureLedgerTest.checkpoint(List.of());
    class Fake implements GitHubErasureCheckpointStore.Transport {
        List<ErasureCheckpoint> records=new ArrayList<>(List.of(first));
        String revision="a".repeat(40);
        ErasureCheckpoint pending;
        boolean truncated,conflict;
        List<String> methods=new ArrayList<>();
        public JsonNode request(String method,String path,Object body) {
            methods.add(method+" "+path);
            var value=json.valueToTree(body);
            if (path.equals("/git/ref/heads/main")) return json.valueToTree(Map.of("object",Map.of("sha",revision)));
            if (path.startsWith("/git/trees/") && method.equals("GET")) {
                var tree=new ArrayList<Object>();
                for(var cp:records) tree.add(Map.of("path",String.format("checkpoints/checkpoint-%020d.json",cp.sequence()),
                        "type","blob","mode","100644","sha",String.format("%040d",cp.sequence())));
                return json.valueToTree(Map.of("truncated",truncated,"tree",tree));
            }
            if (path.startsWith("/git/blobs/") && method.equals("GET")) {
                int index=Integer.parseInt(path.substring("/git/blobs/".length()));
                byte[] bytes=records.get(index-1).bytes();
                return json.valueToTree(Map.of("encoding","base64","size",bytes.length,"content",Base64.getEncoder().encodeToString(bytes)));
            }
            if (path.startsWith("/git/commits/") && method.equals("GET")) return json.valueToTree(Map.of("tree",Map.of("sha","c".repeat(40))));
            if (path.equals("/git/blobs")) {
                try { pending=json.readValue(Base64.getDecoder().decode(value.path("content").asText()),ErasureCheckpoint.class); }
                catch(Exception e) { throw new IllegalStateException(); }
                return json.valueToTree(Map.of("sha","d".repeat(40)));
            }
            if (path.equals("/git/trees")) {
                assertEquals(1,value.path("tree").size());
                assertEquals("c".repeat(40),value.path("base_tree").asText());
                return json.valueToTree(Map.of("sha","e".repeat(40)));
            }
            if (path.equals("/git/commits")) {
                assertEquals(revision,value.path("parents").get(0).asText());
                return json.valueToTree(Map.of("sha","f".repeat(40)));
            }
            if (path.equals("/git/refs/heads/main")) {
                assertFalse(value.path("force").asBoolean(true));
                if(conflict)throw new IllegalStateException("simulated non-fast-forward");
                revision=value.path("sha").asText();records.add(pending);
                return json.createObjectNode();
            }
            throw new AssertionError(method+" "+path);
        }
    }
    ErasureCheckpoint next() {
        return new ErasureCheckpoint(1,first.realm(),first.databaseEpoch(),2,1,"b".repeat(64),first.digest(),"2026-09-07T00:00:00Z");
    }
    @Test void readsExistingGenesisAndNeverCreatesIt() {
        var fake=new Fake();var store=new GitHubErasureCheckpointStore(fake);
        assertEquals(first,store.read().checkpoint());assertTrue(fake.methods.stream().allMatch(s->s.startsWith("GET ")));
    }
    @Test void appendPreservesExistingTreeAndVerifiesRemoteAcknowledgement() {
        var fake=new Fake();var store=new GitHubErasureCheckpointStore(fake);
        store.append(store.read(),next());assertEquals(2,fake.records.size());assertEquals(next(),store.read().checkpoint());
    }
    @Test void concurrentRefChangeCannotBeOverwritten() {
        var fake=new Fake();var store=new GitHubErasureCheckpointStore(fake);var head=store.read();fake.revision="b".repeat(40);
        assertThrows(IllegalStateException.class,()->store.append(head,next()));
        assertTrue(fake.methods.stream().allMatch(s->s.startsWith("GET ")));
    }
    @Test void nonFastForwardStopsInsteadOfForcePushOrRetry() {
        var fake=new Fake();var store=new GitHubErasureCheckpointStore(fake);fake.conflict=true;
        assertThrows(IllegalStateException.class,()->store.append(store.read(),next()));assertEquals(1,fake.records.size());
    }
    @Test void emptyOrTruncatedRemoteCannotEstablishBaseline() {
        var fake=new Fake();var store=new GitHubErasureCheckpointStore(fake);fake.truncated=true;
        assertThrows(IllegalStateException.class,store::read);fake.truncated=false;fake.records.clear();
        assertThrows(IllegalStateException.class,store::read);
    }
    @Test void wrongParentIsRejectedWithoutNetworkWrite() {
        var fake=new Fake();var store=new GitHubErasureCheckpointStore(fake);var bad=new ErasureCheckpoint(1,first.realm(),first.databaseEpoch(),2,1,"b".repeat(64),"c".repeat(64),"2026-09-07T00:00:00Z");
        assertThrows(IllegalStateException.class,()->store.append(store.read(),bad));
        assertTrue(fake.methods.stream().allMatch(s->s.startsWith("GET ")));
    }
    @Test void missingCredentialRejectedWithoutNetwork() {
        for(String value:List.of("","short","newline\ntoken"))
            assertThrows(IllegalStateException.class,()->GitHubErasureCheckpointStore.configured(value));
    }
}
