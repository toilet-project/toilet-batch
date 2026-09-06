package com.example.toiletbatch.account;

import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountSessionCleanerTest {
    @Test void clearsOnlyMemberRefreshAndRecoveryKeys() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members("auth:refresh-user:7")).thenReturn(Set.of("hash"));
        when(sets.members("auth:recovery-user:7")).thenReturn(Set.of("proof"));
        new AccountSessionCleaner(redis).clear(7);
        verify(redis).delete(List.of("auth:refresh-token:hash"));
        verify(redis).delete(List.of("auth:recovery:proof"));
        verify(redis).delete("auth:refresh-user:7");
        verify(redis).delete("auth:recovery-user:7");
    }

    @Test void redisFailureIsNotSwallowed() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForSet()).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> new AccountSessionCleaner(redis).clear(7));
    }
}
