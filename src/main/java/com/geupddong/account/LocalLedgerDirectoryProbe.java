package com.geupddong.account;

import java.nio.file.Path;
import java.util.List;

/** No Spring/DB/network/record writes: verifies the actual Docker bind and process identity. */
public final class LocalLedgerDirectoryProbe {
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !List.of("R2", "LOCAL").contains(args[1])) throw new IllegalArgumentException();
            var store = new FileErasureObjectStore(Path.of("/home/luha/geupddong-erasure-ledger"), "production", args[0]);
            int limit = args[1].equals("R2") ? 0 : 10000;
            int count = 0;
            for (var prefix : List.of("v1/production/", "catalogue-v1/production/", "completion-v1/production/")) {
                for (String key : store.list(prefix, limit)) {
                    if (++count > limit || store.read(key) == null) throw new IllegalStateException();
                }
            }
            System.out.println("LOCAL_DOCKER_DIRECTORY_PASS readable=true");
        } catch (Exception ignored) {
            System.err.println("LOCAL_DOCKER_DIRECTORY_FAILED"); System.exit(1);
        }
    }
}
