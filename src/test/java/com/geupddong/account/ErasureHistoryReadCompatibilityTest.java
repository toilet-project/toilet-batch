package com.geupddong.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import com.geupddong.account.GitHubErasureHistoryStore.*;
import static com.geupddong.account.GitHubErasureHistoryStore.*;

/** Identical fixture is run in API and batch to prevent silent reader format drift. */
class ErasureHistoryReadCompatibilityTest {
    final Instant now=Instant.parse("2026-10-10T00:00:00Z");final String id="00000000-0000-0000-0000-000000000001";
    final ObjectMapper json=new ObjectMapper();
    GitHubErasureCheckpointStore.Transport fixture(int through,boolean omitProof) {
        var base=new ErasureCheckpoint(1,"test",id,1,1,"a".repeat(64),"",now.toString());
        var binding=new Binding("test",id,new UUID(0,99).toString(),"b".repeat(40),base.digest(),1,1,base.inventoryDigest(Map.of()),
                "c".repeat(64),"d".repeat(64),now.toString(),now.plusSeconds(600).toString());
        var files=new TreeMap<String,byte[]>();files.put(checkpointPath(1),base.bytes());Phase previous=null;
        for(int p=0;p<=through;p++) {
            var phase=new Phase(2,p,binding,previous==null?"":previous.digest(),now.toString());
            if(!omitProof)files.put(phasePath(1,p),phase.bytes());previous=phase;
        }
        if(through>=7)files.put(checkpointPath(2),new ErasureCheckpoint(1,"test",id,2,0,binding.afterInventoryDigest(),base.digest(),now.toString()).bytes());
        var blobs=new HashMap<String,byte[]>();var tree=new ArrayList<Object>();int counter=0;
        for(var entry:files.entrySet()) {
            String sha=String.format(Locale.ROOT,"%040d",++counter);blobs.put(sha,entry.getValue());
            tree.add(Map.of("path",entry.getKey(),"type","blob","mode","100644","sha",sha));
        }
        return(method,path,body)->{
            assertEquals("GET",method);
            if(path.equals("/git/ref/heads/main"))return json.valueToTree(Map.of("object",Map.of("sha","b".repeat(40))));
            if(path.startsWith("/git/trees/"))return json.valueToTree(Map.of("truncated",false,"tree",tree));
            if(path.startsWith("/git/blobs/")) {
                byte[] bytes=blobs.get(path.substring(11));return json.valueToTree(Map.of("encoding","base64","size",bytes.length,
                        "content",Base64.getEncoder().encodeToString(bytes)));
            }
            throw new AssertionError(path);
        };
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7})
    void allUncleanedPhasesBlockBothReaderVersions(int phase) {
        var transport=fixture(phase,false);
        assertThrows(IllegalStateException.class,()->new GitHubErasureHistoryStore(transport,Clock.fixed(now,ZoneOffset.UTC)).read());
        assertThrows(IllegalStateException.class,()->new GitHubErasureCheckpointStore(transport).read());
    }
    @Test void cleanedProofAllowsNewReaderButNeverSilentlyAllowsOldReader() {
        var transport=fixture(8,false);assertEquals(0,new GitHubErasureHistoryStore(transport,Clock.fixed(now,ZoneOffset.UTC)).read().checkpoint().count());
        assertThrows(IllegalStateException.class,()->new GitHubErasureCheckpointStore(transport).read());
    }
    @Test void missingRetirementProofCannotJustifyCountDecrease() {
        assertThrows(IllegalStateException.class,()->new GitHubErasureHistoryStore(fixture(8,true),Clock.fixed(now,ZoneOffset.UTC)).read());
    }
    @Test void originalBaselineIsStillReadable() {
        assertEquals(1,new GitHubErasureHistoryStore(fixture(-1,false),Clock.fixed(now,ZoneOffset.UTC)).read().checkpoint().count());
    }
}
