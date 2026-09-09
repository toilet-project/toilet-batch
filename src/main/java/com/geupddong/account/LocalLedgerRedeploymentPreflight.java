package com.geupddong.account;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Paused LOCAL redeployment only. No Spring, DB, R2 client, bootstrap or record writer. */
public final class LocalLedgerRedeploymentPreflight {
    static ErasureCipher cipher(String encoded, String active) throws Exception {
        if (encoded == null || encoded.length() > 8192) throw new IllegalArgumentException();
        var node = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(encoded);
        if (!node.isObject()) throw new IllegalArgumentException();
        Map<String, String> keys = new HashMap<>();
        for (var field : node.properties()) {
            if (!field.getValue().isTextual()) throw new IllegalArgumentException();
            String value = field.getValue().textValue();
            if (!Base64.getEncoder().encodeToString(Base64.getDecoder().decode(value)).equals(value))
                throw new IllegalArgumentException();
            keys.put(field.getKey(), value);
        }
        return new ErasureCipher(active, keys);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException();
        return value;
    }

    public static void main(String[] args) {
        try {
            if (args.length != 0 || !env("LOCAL_PREFLIGHT_READONLY").equals("approved")) throw new IllegalStateException();
            Path root = Path.of("/home/luha/geupddong-erasure-ledger");
            for (String field : List.of("unix:uid", "unix:gid"))
                if (((Number) Files.getAttribute(root, field, LinkOption.NOFOLLOW_LINKS)).intValue() != 1000)
                    throw new IllegalStateException();
            var local = new FileErasureObjectStore(root, "production", env("LOCAL_STORE_ID"));
            ErasureLedgerMigration.verifyReadOnly(local, cipher(env("LEDGER_KEYS_JSON"), env("LEDGER_ACTIVE_KEY_ID")),
                    GitHubErasureCheckpointStore.configured(env("CHECKPOINT_TOKEN")),
                    "production", env("DATABASE_EPOCH"), Clock.systemUTC());
            System.out.println("LOCAL_REDEPLOYMENT_PREFLIGHT_PASS inventoryVerified=true activationAllowed=false");
        } catch (Exception ignored) {
            System.err.println("LOCAL_REDEPLOYMENT_PREFLIGHT_FAILED detailsSuppressed=true");
            System.exit(1);
        }
    }
}
