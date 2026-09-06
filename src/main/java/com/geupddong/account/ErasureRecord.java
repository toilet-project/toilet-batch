package com.geupddong.account;

import java.time.LocalDateTime;
import java.util.UUID;

/** Identifiers needed to suppress resurrection from backups; no email/profile/provider content. */
public record ErasureRecord(int version, String realm, long userId, String userCreatedAt,
                            String withdrawalKey, String eligibleAt) {
    public ErasureRecord {
        if (version != 1 || realm == null || !realm.matches("[a-z0-9-]{3,40}") || userId <= 0)
            throw new IllegalArgumentException("INVALID_ERASURE_RECORD");
        LocalDateTime.parse(userCreatedAt);
        LocalDateTime.parse(eligibleAt);
        if (!UUID.fromString(withdrawalKey).toString().equals(withdrawalKey))
            throw new IllegalArgumentException("INVALID_ERASURE_RECORD");
    }

    public String objectKey() { return "v1/" + realm + "/" + withdrawalKey + ".bin"; }
}
