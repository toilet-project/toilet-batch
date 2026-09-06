package com.geupddong.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Immutable observation of absence, not the intent time or a license to delete backups. */
public record ErasureCompletion(int version, String realm, String withdrawalKey,
                                String intentDigest, String databaseEpoch, String firstConfirmedAbsentAt) {
    public ErasureCompletion {
        try {
            if (version != 1 || realm == null || !realm.matches("[a-z0-9-]{3,40}")
                    || !UUID.fromString(withdrawalKey).toString().equals(withdrawalKey)
                    || !UUID.fromString(databaseEpoch).toString().equals(databaseEpoch)
                    || intentDigest == null || !intentDigest.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException();
            if (!Instant.parse(firstConfirmedAbsentAt).toString().equals(firstConfirmedAbsentAt))
                throw new IllegalArgumentException();
        } catch (RuntimeException ignored) { throw new IllegalArgumentException("INVALID_ERASURE_COMPLETION"); }
    }

    public String objectKey() {
        return "completion-v1/" + realm + "/" + databaseEpoch + "/" + withdrawalKey + ".bin";
    }

    public static ErasureCompletion observed(ErasureRecord intent, String epoch, Instant at) {
        return new ErasureCompletion(1, intent.realm(), intent.withdrawalKey(), digest(intent), epoch, at.toString());
    }

    /** Domain-separated digest of all original identity fields; not ciphertext (nonce changes). */
    public static String digest(ErasureRecord intent) {
        try {
            String canonical = String.join("\n", "geupddong-erasure-intent-digest-v1",
                    Integer.toString(intent.version()), intent.realm(), Long.toString(intent.userId()),
                    intent.userCreatedAt(), intent.withdrawalKey(), intent.eligibleAt());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) { throw new IllegalStateException("ERASURE_COMPLETION_DIGEST_FAILED"); }
    }
}
