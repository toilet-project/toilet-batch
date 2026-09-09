package com.geupddong.account;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.account.GitHubErasureHistoryStore.*;

/** Explicit one-off synthetic protocol probe. No member identifiers, journal bytes or production ref writes. */
public final class RetirementGitHubVerification {
    static final String BRANCH="retirement-verification-20260910";
    static final String EPOCH="00000000-0000-0000-0000-000000000001";
    private RetirementGitHubVerification(){}
    static final class Scoped implements GitHubErasureCheckpointStore.Transport {
        final GitHubErasureCheckpointStore.Transport delegate;int requests;boolean loseAck;
        Scoped(GitHubErasureCheckpointStore.Transport delegate){this.delegate=delegate;}
        public JsonNode request(String method,String path,Object body) {
            if(++requests>500)throw new IllegalStateException("SYNTHETIC_REQUEST_BUDGET");
            if(path.equals("/git/ref/heads/main"))path="/git/ref/heads/"+BRANCH;
            if(path.equals("/git/refs/heads/main"))path="/git/refs/heads/"+BRANCH;
            if(method.equals("GET")) {
                if(!path.equals("/git/ref/heads/"+BRANCH) && !path.matches("/git/(trees|blobs|commits)/[a-f0-9]{40}(\\?recursive=1)?"))throw new IllegalStateException();
            }else if(method.equals("PATCH")) {
                if(!path.equals("/git/refs/heads/"+BRANCH))throw new IllegalStateException();
                var value=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body);
                if(value.path("force").asBoolean(true))throw new IllegalStateException();
            }else if(!method.equals("POST") || !Set.of("/git/blobs","/git/trees","/git/commits").contains(path))throw new IllegalStateException();
            var result=delegate.request(method,path,body);
            if(method.equals("PATCH") && loseAck){loseAck=false;throw new IllegalStateException("SYNTHETIC_LOST_ACK");}
            return result;
        }
    }
    static String sha(JsonNode n){String s=n.path("sha").asText();if(!s.matches("[a-f0-9]{40}"))throw new IllegalStateException();return s;}
    static String mainHead(GitHubErasureCheckpointStore.Transport raw) {
        return sha(raw.request("GET","/git/ref/heads/main",null).path("object"));
    }
    static void bootstrap(GitHubErasureCheckpointStore.Transport raw) {
        var cp=new ErasureCheckpoint(1,"verification",EPOCH,1,2,"a".repeat(64),"",Instant.now().toString());
        String blob=sha(raw.request("POST","/git/blobs",Map.of("encoding","base64","content",Base64.getEncoder().encodeToString(cp.bytes()))));
        String tree=sha(raw.request("POST","/git/trees",Map.of("tree",List.of(Map.of("path",GitHubErasureHistoryStore.checkpointPath(1),
                "mode","100644","type","blob","sha",blob)))));
        String commit=sha(raw.request("POST","/git/commits",Map.of("message","Synthetic retirement protocol verification only","tree",tree,"parents",List.of())));
        raw.request("POST","/git/refs",Map.of("ref","refs/heads/"+BRANCH,"sha",commit)); // Create-only; existing branch fails, never reset.
    }
    static void retire(GitHubErasureHistoryStore history,Scoped transport,long number,boolean loseCommitAck) {
        var head=history.read();var cp=head.checkpoint();var now=Instant.now();
        var binding=new Binding("verification",EPOCH,new UUID(0,number+100).toString(),head.revision(),cp.digest(),cp.sequence(),cp.count(),
                "b".repeat(64),"c".repeat(64),"d".repeat(64),now.toString(),now.plusSeconds(600).toString());
        history.appendPhase(head.revision(),new Phase(2,0,binding,"",Instant.now().toString()));
        for(int p=1;p<=8;p++) {
            var view=history.inspect();var previous=view.latest().getLast();
            var phase=new Phase(2,p,binding,previous.digest(),Instant.now().toString());
            if(p==7 && loseCommitAck) {
                transport.loseAck=true;assertThrows(IllegalStateException.class,()->history.appendPhase(view.head().revision(),phase));
                assertEquals(7,history.inspect().latest().getLast().phase());
            }else history.appendPhase(view.head().revision(),phase);
            if(p<8)assertThrows(IllegalStateException.class,history::read);
        }
        assertEquals(cp.count()-1,history.read().checkpoint().count());
    }
    public static void main(String[] args) {
        try {
            if(args.length!=0 || !"approved".equals(System.getenv("RETIREMENT_GITHUB_SYNTHETIC_APPROVED")))throw new IllegalStateException();
            var raw=GitHubErasureCheckpointStore.configuredTransport(System.getenv("RETIREMENT_GITHUB_SYNTHETIC_TOKEN"));
            String originalMain=mainHead(raw);bootstrap(raw);
            var transport=new Scoped(raw);var history=new GitHubErasureHistoryStore(transport,Clock.systemUTC());
            retire(history,transport,1,true);
            var head=history.read();var cp=head.checkpoint();
            var next=new ErasureCheckpoint(1,cp.realm(),cp.databaseEpoch(),cp.sequence()+1,cp.count()+1,"e".repeat(64),cp.digest(),Instant.now().toString());
            history.append(head,next);assertThrows(IllegalStateException.class,()->history.append(head,next));
            retire(history,transport,2,false);
            assertThrows(IllegalStateException.class,()->new GitHubErasureCheckpointStore(transport).read());
            assertEquals(originalMain,mainHead(raw),"production main must remain unchanged");
            System.out.printf("PASS synthetic-github two-retirements normal-append lost-ack stale-cas legacy-block main-unchanged requests=%d%n",transport.requests);
        }catch(Exception | AssertionError ignored){System.err.println("SYNTHETIC_GITHUB_VERIFICATION_HELD; keep test branch for inspection");System.exit(2);}
    }
}
