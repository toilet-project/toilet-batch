package com.example.toiletbatch.account;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Same namespaces as the API; never scan unrelated Redis keys or log tokens. */
@Service
public class AccountSessionCleaner {
    private final StringRedisTemplate redis;
    public AccountSessionCleaner(StringRedisTemplate redis) { this.redis = redis; }

    public void clear(long userId) {
        clearIndex("auth:refresh-user:" + userId, "auth:refresh-token:");
        clearIndex("auth:recovery-user:" + userId, "auth:recovery:");
    }

    private void clearIndex(String index, String prefix) {
        var members = redis.opsForSet().members(index);
        if (members != null && !members.isEmpty())
            redis.delete(members.stream().map(member -> prefix + member).toList());
        redis.delete(index);
    }
}
