package com.geupddong.account;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import com.geupddong.account.RetirementRecoveryJournal.Entry;
import com.geupddong.account.RetirementRecoveryCoordinator.State;

/** Aggregate-only append-only retirement barrier. Experimental, no production factory wiring.
 * Records are deliberately inside checkpoints/: unmodified v1 consumers reject them, even at phase 7.
 * Supports one transition only. Never remove this barrier to make old consumers appear healthy.
 */
final class GitHubRetirementRecoveryStore implements RetirementRecoveryCoordinator.Independent {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Binding(String realm,String epoch,String baseRevision,String checkpointDigest,long checkpointSequence,
                   int beforeCount,String afterInventoryDigest,String journalDigest,String planDigest,
                   String preparedAt,String validUntil) {
        Binding {
            try {
                if(realm==null || !realm.matches("[a-z0-9-]{3,40}") || !UUID.fromString(epoch).toString().equals(epoch)
                        || !gitSha(baseRevision) || !hash(checkpointDigest) || !hash(afterInventoryDigest)
                        || !hash(journalDigest) || !hash(planDigest) || checkpointSequence<1 || checkpointSequence>100000
                        || beforeCount<1 || beforeCount>100000) throw failure();
                Instant start=Instant.parse(preparedAt),end=Instant.parse(validUntil);
                if(!start.toString().equals(preparedAt) || !end.toString().equals(validUntil)
                        || end.isBefore(start) || end.isAfter(start.plusSeconds(600))) throw failure();
            } catch(RuntimeException ignored) {throw failure();}
        }
    }
    record Phase(int version,int phase,Binding binding,String previousPhaseDigest,String recordedAt) {
        Phase {
            try {
                if(version!=1 || phase<0 || phase>7 || binding==null
                        || (phase==0 ? !"".equals(previousPhaseDigest) : !hash(previousPhaseDigest))) throw failure();
                Instant at=Instant.parse(recordedAt);
                if(!at.toString().equals(recordedAt) || at.isBefore(Instant.parse(binding.preparedAt()))
                        || at.isAfter(Instant.parse(binding.validUntil()))) throw failure();
            } catch(RuntimeException ignored) {throw failure();}
        }
        byte[] bytes() {try {return JSON.writeValueAsBytes(this);} catch(Exception e) {throw failure();}}
        String digest() {return ErasureCheckpoint.hash(bytes());}
    }
    record CommittedInventory(String realm,String epoch,int count,String inventorySha256,String revision) { }
    private record Snapshot(CheckpointedErasureLedger.Head base,List<Phase> phases) {
        State state() {return phases.isEmpty()?null:new State(base.revision(),phases.getLast().binding().journalDigest(),phases.size()-1);}
    }
    private final GitHubErasureCheckpointStore.Transport transport;
    private final String realm,epoch;
    private final Clock clock;
    GitHubRetirementRecoveryStore(GitHubErasureCheckpointStore.Transport transport,String realm,String epoch,Clock clock) {
        this.transport=Objects.requireNonNull(transport);this.realm=realm;this.epoch=epoch;this.clock=Objects.requireNonNull(clock);
    }
    static GitHubRetirementRecoveryStore configured(String token,String realm,String epoch,Clock clock) {
        return new GitHubRetirementRecoveryStore(GitHubErasureCheckpointStore.configuredTransport(token),realm,epoch,clock);
    }
    private JsonNode call(String method,String path,Object body) {
        try {return Objects.requireNonNull(transport.request(method,path,body));} catch(RuntimeException ignored) {throw failure();}
    }
    private static String sha(JsonNode node) {String s=node.asText();if(!gitSha(s)) throw failure();return s;}
    private Phase phase(String sha) {
        try {
            JsonNode response=call("GET","/git/blobs/"+sha,null);
            if(!"base64".equals(response.path("encoding").asText()) || !response.path("size").canConvertToInt()
                    || response.path("size").asInt()<1 || response.path("size").asInt()>4096) throw failure();
            byte[] bytes=Base64.getMimeDecoder().decode(response.path("content").asText());
            if(bytes.length!=response.path("size").asInt() || bytes.length>4096) throw failure();
            Phase phase=JSON.readValue(bytes,Phase.class);
            if(!Arrays.equals(bytes,phase.bytes())) throw failure();return phase;
        } catch(Exception ignored) {throw failure();}
    }
    private Snapshot snapshot() {
        try { return checkedSnapshot(); } catch(RuntimeException ignored) {throw failure();}
    }
    private Snapshot checkedSnapshot() {
        String revision=sha(call("GET","/git/ref/heads/main",null).path("object").path("sha"));
        JsonNode tree=call("GET","/git/trees/"+revision+"?recursive=1",null);
        if(!tree.has("truncated") || tree.path("truncated").asBoolean(true) || !tree.path("tree").isArray()
                || tree.path("tree").size()>100100) throw failure();
        var phases=new TreeMap<Integer,String>(); var filtered=((ObjectNode)tree).deepCopy();
        var items=JSON.createArrayNode();
        for(JsonNode node:tree.path("tree")) {
            String path=node.path("path").asText();
            if(path.matches("checkpoints/retirement-phase-[0-7]\\.json")) {
                int number=path.charAt("checkpoints/retirement-phase-".length())-'0';
                if(!"blob".equals(node.path("type").asText()) || !"100644".equals(node.path("mode").asText())
                        || phases.put(number,sha(node.path("sha")))!=null) throw failure();
            } else items.add(node); // Unknown checkpoint files still reach and fail the strict v1 reader.
        }
        filtered.set("tree",items);
        // Pin every legacy read to this revision; never mix a new head with an old phase snapshot.
        var legacy=new GitHubErasureCheckpointStore((method,path,body)->{
            if(!method.equals("GET")) throw failure();
            if(path.equals("/git/ref/heads/main")) return JSON.valueToTree(Map.of("object",Map.of("sha",revision)));
            if(path.equals("/git/trees/"+revision+"?recursive=1")) return filtered;
            return call(method,path,body);
        });
        var base=legacy.read();var cp=base.checkpoint();
        if(!realm.equals(cp.realm()) || !epoch.equals(cp.databaseEpoch())
                || Instant.parse(cp.recordedAt()).isAfter(clock.instant())) throw failure();
        if(!phases.isEmpty() && (phases.firstKey()!=0 || phases.lastKey()!=phases.size()-1)) throw failure();
        var decoded=new ArrayList<Phase>();
        for(var item:phases.entrySet()) {
            Phase next=phase(item.getValue());Binding b=next.binding();
            if(next.phase()!=item.getKey() || !b.realm().equals(realm) || !b.epoch().equals(epoch)
                    || !b.checkpointDigest().equals(cp.digest()) || b.checkpointSequence()!=cp.sequence()
                    || b.beforeCount()!=cp.count() || Instant.parse(next.recordedAt()).isAfter(clock.instant())) throw failure();
            if(!decoded.isEmpty()) {
                Phase previous=decoded.getLast();
                if(!next.binding().equals(previous.binding()) || !next.previousPhaseDigest().equals(previous.digest())
                        || Instant.parse(next.recordedAt()).isBefore(Instant.parse(previous.recordedAt()))) throw failure();
            }
            decoded.add(next);
        }
        return new Snapshot(base,List.copyOf(decoded));
    }
    @Override public State read() {return snapshot().state();}
    private Binding binding(Entry entry,ErasureCheckpoint cp) {
        if(!entry.realm().equals(realm) || !entry.epoch().equals(epoch) || !entry.checkpointDigest().equals(cp.digest())
                || entry.beforeCount()!=cp.count()) throw failure();
        return new Binding(realm,epoch,entry.checkpointRevision(),cp.digest(),cp.sequence(),cp.count(),
                entry.afterInventoryDigest(),entry.digest(),entry.planDigest(),entry.preparedAt(),entry.validUntil());
    }
    @Override public void prepare(Entry entry) {
        Snapshot before=snapshot();
        if(before.state()!=null || !before.base().revision().equals(entry.checkpointRevision())) throw failure();
        Phase next=new Phase(1,0,binding(entry,before.base().checkpoint()),"",clock.instant().toString());
        append(before,next);
    }
    @Override public void advance(State expected,Entry entry,int nextPhase) {
        Snapshot before=snapshot();
        if(!Objects.equals(before.state(),expected) || expected==null || nextPhase!=expected.phase()+1 || nextPhase>7) throw failure();
        Phase previous=before.phases().getLast();
        if(!binding(entry,before.base().checkpoint()).equals(previous.binding())) throw failure();
        Phase next=new Phase(1,nextPhase,previous.binding(),previous.digest(),clock.instant().toString());
        if(Instant.parse(next.recordedAt()).isBefore(Instant.parse(previous.recordedAt()))) throw failure();
        append(before,next);
    }
    /** Deliberate new-format result. Existing v1 callers must not resume from this by dropping the barrier. */
    CommittedInventory committedInventory() {
        Snapshot snapshot=snapshot();if(snapshot.state()==null || snapshot.state().phase()!=7) throw failure();
        Binding b=snapshot.phases().getLast().binding();
        return new CommittedInventory(realm,epoch,b.beforeCount()-1,b.afterInventoryDigest(),snapshot.base().revision());
    }
    private void append(Snapshot before,Phase next) {
        String oldRevision=before.base().revision();
        String baseTree=sha(call("GET","/git/commits/"+oldRevision,null).path("tree").path("sha"));
        String blob=sha(call("POST","/git/blobs",Map.of("encoding","base64","content",Base64.getEncoder().encodeToString(next.bytes()))).path("sha"));
        String tree=sha(call("POST","/git/trees",Map.of("base_tree",baseTree,"tree",List.of(Map.of(
                "path","checkpoints/retirement-phase-"+next.phase()+".json","mode","100644","type","blob","sha",blob)))).path("sha"));
        String revision=sha(call("POST","/git/commits",Map.of("message","Record verified retirement transition",
                "tree",tree,"parents",List.of(oldRevision))).path("sha"));
        call("PATCH","/git/refs/heads/main",Map.of("sha",revision,"force",false));
        Snapshot confirmed=snapshot();
        if(!confirmed.base().revision().equals(revision) || !confirmed.phases().getLast().equals(next)) throw failure();
    }
    private static boolean gitSha(String v) {return v!=null && v.matches("[a-f0-9]{40}");}
    private static boolean hash(String v) {return RetirementRecoveryJournal.hash(v);}
    private static IllegalStateException failure() {return new IllegalStateException("RETIREMENT_GITHUB_UNAVAILABLE");}
}
