package com.geupddong.account;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Only aggregate records reach the independently hosted, dedicated private repository. */
public final class GitHubErasureCheckpointStore implements CheckpointedErasureLedger.Store {
    @FunctionalInterface public interface Transport { JsonNode request(String method, String path, Object body); }
    private final Transport transport;
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public GitHubErasureCheckpointStore(Transport transport) { this.transport=transport; }
    public static GitHubErasureCheckpointStore configured(String token) {
        return new GitHubErasureCheckpointStore(configuredTransport(token));
    }
    static Transport configuredTransport(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_]{20,255}")) throw failure();
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return (method,path,body)->{
            try {
                if (!path.matches("/[A-Za-z0-9/?.=_-]+")) throw failure();
                var builder=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/toilet-project/operations-checkpoints"+path))
                        .timeout(Duration.ofSeconds(10)).header("Accept","application/vnd.github+json")
                        .header("X-GitHub-Api-Version","2022-11-28").header("Authorization","Bearer "+token)
                        .header("User-Agent","geupddong-erasure-checkpoints")
                        .header("Content-Type","application/json");
                var request=builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
                        HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
                try (var stream=response.body()) {
                    byte[] bytes=stream.readNBytes(8*1024*1024+1);
                    if (response.statusCode()<200 || response.statusCode()>=300 || bytes.length>8*1024*1024) throw failure();
                    return mapper.readTree(bytes);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure(); }
            catch (Exception ignored) { throw failure(); }
        };
    }
    private JsonNode call(String method,String path,Object body) {
        try { return transport.request(method,path,body); } catch (RuntimeException ignored) { throw failure(); }
    }
    private static String sha(JsonNode node) {
        String value=node.asText();
        if (!value.matches("[a-f0-9]{40}")) throw failure();
        return value;
    }
    private ErasureCheckpoint blob(String hash) {
        try {
            var response=call("GET","/git/blobs/"+hash,null);
            if (!"base64".equals(response.path("encoding").asText()) || response.path("size").asInt(-1)>1024) throw failure();
            byte[] bytes=Base64.getMimeDecoder().decode(response.path("content").asText());
            if (bytes.length>1024) throw failure();
            var cp=json.readValue(bytes,ErasureCheckpoint.class);
            if (!Arrays.equals(bytes,cp.bytes())) throw failure();
            return cp;
        } catch (Exception ignored) { throw failure(); }
    }
    @Override public CheckpointedErasureLedger.Head read() {
        String revision=sha(call("GET","/git/ref/heads/main",null).path("object").path("sha"));
        var tree=call("GET","/git/trees/"+revision+"?recursive=1",null);
        if (!tree.has("truncated") || tree.path("truncated").asBoolean(true) || !tree.path("tree").isArray()) throw failure();
        var entries=new TreeMap<Long,String>();
        for (var item:tree.path("tree")) {
            String path=item.path("path").asText();
            if (!path.startsWith("checkpoints/") || path.equals("checkpoints/.gitkeep")) continue;
            if (!path.matches("checkpoints/checkpoint-[0-9]{20}\\.json")
                    || !"blob".equals(item.path("type").asText()) || !"100644".equals(item.path("mode").asText())) throw failure();
            long sequence;
            try { sequence=Long.parseLong(path.substring(23,43)); } catch (Exception e) { throw failure(); }
            if (sequence<1 || sequence>100000 || entries.put(sequence,sha(item.path("sha")))!=null) throw failure();
        }
        if (entries.isEmpty() || entries.lastKey()!=entries.size()) throw failure(); // Never automatic genesis.
        var latest=blob(entries.lastEntry().getValue());
        if (latest.sequence()!=entries.lastKey()) throw failure();
        if (latest.sequence()==1) {
            if (!latest.previousCheckpointSha256().isEmpty()) throw failure();
        } else {
            var previous=blob(entries.get(latest.sequence()-1));
            if (!latest.previousCheckpointSha256().equals(previous.digest())
                    || !latest.realm().equals(previous.realm()) || !latest.databaseEpoch().equals(previous.databaseEpoch())
                    || latest.count()<previous.count() || previous.sequence()!=latest.sequence()-1
                    || java.time.Instant.parse(latest.recordedAt()).isBefore(java.time.Instant.parse(previous.recordedAt()))) throw failure();
        }
        return new CheckpointedErasureLedger.Head(revision,latest);
    }
    @Override public void append(CheckpointedErasureLedger.Head expected, ErasureCheckpoint next) {
        var previous=expected.checkpoint();
        if (next.sequence()!=previous.sequence()+1 || !next.previousCheckpointSha256().equals(previous.digest())
                || next.count()!=previous.count()+1 || !next.realm().equals(previous.realm())
                || !next.databaseEpoch().equals(previous.databaseEpoch())
                || java.time.Instant.parse(next.recordedAt()).isBefore(java.time.Instant.parse(previous.recordedAt()))) throw failure();
        if (!read().equals(expected)) throw failure();
        var commit=call("GET","/git/commits/"+expected.revision(),null);
        String baseTree=sha(commit.path("tree").path("sha"));
        String blob=sha(call("POST","/git/blobs",Map.of("encoding","base64",
                "content",Base64.getEncoder().encodeToString(next.bytes()))).path("sha"));
        String tree=sha(call("POST","/git/trees",Map.of("base_tree",baseTree,"tree",List.of(Map.of(
                "path",String.format(java.util.Locale.ROOT,"checkpoints/checkpoint-%020d.json",next.sequence()),
                "mode","100644","type","blob","sha",blob)))).path("sha"));
        String revision=sha(call("POST","/git/commits",Map.of("message","Record verified erasure inventory checkpoint",
                "tree",tree,"parents",List.of(expected.revision()))).path("sha"));
        // Single-parent fast-forward only: a concurrent update cannot be overwritten.
        call("PATCH","/git/refs/heads/main",Map.of("sha",revision,"force",false));
        var confirmed=read();
        if (!confirmed.revision().equals(revision) || !confirmed.checkpoint().equals(next)) throw failure();
    }
    private static IllegalStateException failure() { return new IllegalStateException("ERASURE_CHECKPOINT_GITHUB_UNAVAILABLE"); }
}
