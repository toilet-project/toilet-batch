package com.geupddong.account;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="ERASURE_CHECKPOINT_READONLY_CHECK",matches="true")
class CheckpointReadinessLiveTest {
    @Test void readsExistingIndependentCheckpointWithoutAnyWrite() {
        String token=System.getenv("ERASURE_CHECKPOINT_GITHUB_TOKEN");
        String epoch=System.getenv("ERASURE_CHECKPOINT_DATABASE_EPOCH");
        assertNotNull(epoch,"independent expected epoch required");
        var head=GitHubErasureCheckpointStore.configured(token).read();
        assertEquals("production",head.checkpoint().realm());
        assertEquals(epoch,head.checkpoint().databaseEpoch());
        assertFalse(java.time.Instant.parse(head.checkpoint().recordedAt()).isAfter(java.time.Instant.now()));
        System.out.println("CHECKPOINT_READONLY_OK identityMatched=true writes=false");
    }
}
