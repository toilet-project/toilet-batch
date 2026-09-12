package com.geupddong.account;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewCheckpointStoreTest {
    @Test void onlyDedicatedBranchCanBeReadOrAdvanced(){
        var paths=new ArrayList<String>();
        var scoped=ReviewCheckpointStore.scoped((method,path,body)->{paths.add(method+" "+path);return NullNode.instance;});
        scoped.request("GET","/git/ref/heads/main",null);scoped.request("PATCH","/git/refs/heads/main",Map.of("sha","a".repeat(40)));
        assertEquals(List.of("GET /git/ref/heads/review-anonymization-v1","PATCH /git/refs/heads/review-anonymization-v1"),paths);
        assertThrows(IllegalStateException.class,()->scoped.request("POST","/git/refs",null));
        assertThrows(IllegalStateException.class,()->scoped.request("PATCH","/git/refs/heads/other",null));
        assertEquals(2,paths.size());
    }
}
