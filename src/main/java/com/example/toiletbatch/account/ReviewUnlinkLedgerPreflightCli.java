package com.example.toiletbatch.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geupddong.account.*;
import com.geupddong.review.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.env.StandardEnvironment;

/** Review-author unlink ledger genesis generation and read-only preflight. No DB or Redis access. */
public final class ReviewUnlinkLedgerPreflightCli {
    private static final String DIRECTORY="/home/luha/geupddong-review-unlink-ledger";
    private static final String ACCOUNT_DIRECTORY="/home/luha/geupddong-erasure-ledger";
    private ReviewUnlinkLedgerPreflightCli() { }

    public static void main(String[] args) {
        String stage="arguments";
        try {
            if(args.length!=1)throw new IllegalArgumentException();
            var env=new StandardEnvironment();
            String epoch=env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH");
            if("--genesis".equals(args[0])) {
                stage="genesis";
                System.out.print(new String(genesis(epoch,Clock.systemUTC().instant()).bytes(),StandardCharsets.UTF_8));
                return;
            }
            if(!"--read-only".equals(args[0]) || !"approved".equals(env.getProperty("REVIEW_UNLINK_PREFLIGHT_READONLY")))
                throw new IllegalArgumentException();
            stage="configuration";
            if(!"LOCAL".equals(env.getRequiredProperty("ERASURE_LEDGER_PROVIDER")))throw new IllegalStateException();
            var directory=Path.of(env.getRequiredProperty("REVIEW_UNLINK_DIRECTORY")).toAbsolutePath().normalize();
            var accountDirectory=Path.of(env.getRequiredProperty("ERASURE_LEDGER_LOCAL_DIRECTORY")).toAbsolutePath().normalize();
            if(!DIRECTORY.equals(directory.toString()) || !ACCOUNT_DIRECTORY.equals(accountDirectory.toString())
                    || directory.startsWith(accountDirectory) || accountDirectory.startsWith(directory))throw new IllegalStateException();
            String storeId=env.getRequiredProperty("REVIEW_UNLINK_STORE_ID");
            int expected=Integer.parseInt(env.getRequiredProperty("REVIEW_UNLINK_EXPECTED_OBJECTS"));
            if(expected<0||expected>100000)throw new IllegalStateException();
            var keys=new ObjectMapper().readValue(env.getRequiredProperty("ERASURE_LEDGER_KEYS_JSON"),new TypeReference<Map<String,String>>(){});
            stage="snapshot";
            try(var objects=new FileErasureObjectStore(directory,ReviewUnlinkRecord.REALM,storeId);
                var journal=new ReviewUnlinkJournal(objects,
                        new ErasureCipher(env.getRequiredProperty("ERASURE_LEDGER_ACTIVE_KEY_ID"),keys),
                        ReviewCheckpointStore.configured(env.getRequiredProperty("ERASURE_CHECKPOINT_GITHUB_TOKEN")),
                        Runnable::run,epoch,Clock.systemUTC())) {
                var snapshot=journal.snapshot();
                if(snapshot.records().size()!=expected)throw new IllegalStateException();
                System.out.printf("REVIEW_AUTHOR_UNLINK_PREFLIGHT_PASS records=%d checkpointMatched=true localStoreVerified=true activationAllowed=false%n",expected);
            }
        } catch(Exception ignored) {
            System.err.println("REVIEW_AUTHOR_UNLINK_PREFLIGHT_FAILED stage="+stage+" detailsSuppressed=true");
            System.exit(1);
        }
    }

    static ErasureCheckpoint genesis(String epoch,Instant recordedAt) {
        var seed=new ErasureCheckpoint(1,ReviewUnlinkRecord.REALM,epoch,1,0,"0".repeat(64),"",recordedAt.toString());
        return new ErasureCheckpoint(1,ReviewUnlinkRecord.REALM,epoch,1,0,seed.inventoryDigest(Map.of()),"",recordedAt.toString());
    }
}
