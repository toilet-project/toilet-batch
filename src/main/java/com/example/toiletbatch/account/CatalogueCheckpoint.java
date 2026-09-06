package com.example.toiletbatch.account;

import java.time.Instant;
import java.util.UUID;
import java.util.HexFormat;
import java.security.MessageDigest;

/** Aggregate-only checkpoint for a separate private repository. Contains no member-level entries. */
public record CatalogueCheckpoint(int version, String realm, String databaseEpoch, long sequence,
                                  int count, String inventorySha256, String previousCheckpointSha256,
                                  String recordedAt) {
    public CatalogueCheckpoint {
        try {
            if(version!=1 || !realm.matches("[a-z0-9-]{3,40}")
                    || !UUID.fromString(databaseEpoch).toString().equals(databaseEpoch)
                    || sequence<1 || count<0 || count>100000 || !inventorySha256.matches("[a-f0-9]{64}")
                    || !(previousCheckpointSha256.isEmpty() || previousCheckpointSha256.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException();
            Instant.parse(recordedAt);
        } catch(RuntimeException ignored){throw new IllegalArgumentException("CHECKPOINT_INVALID");}
    }
    public byte[] bytes() {
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(this);}
        catch(Exception ignored){throw new IllegalStateException("CHECKPOINT_INVALID");}
    }
    public String digest() {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes()));}
        catch(Exception ignored){throw new IllegalStateException("CHECKPOINT_INVALID");}
    }
    /** Approval means actual member-set comparison was reviewed, NOT merely that count increased. */
    public static CatalogueCheckpoint next(CatalogueCheckpoint previous, ErasureIntentInventory verified,
                                            String expectedPreviousDigest, Instant now,
                                            boolean membershipChangeReviewed) {
        if(!membershipChangeReviewed)throw new IllegalStateException("CHECKPOINT_REVIEW_REQUIRED");
        try{
            if(previous==null){
                if(!"GENESIS".equals(expectedPreviousDigest))throw new IllegalStateException();
            }else{
                if(!previous.digest().equals(expectedPreviousDigest) || !previous.realm().equals(verified.realm())
                        || now.isBefore(Instant.parse(previous.recordedAt()))
                        || verified.intentDigests().size()<previous.count()
                        || !previous.databaseEpoch().equals(verified.databaseEpoch()))throw new IllegalStateException();
            }
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ErasureCatalogueExportCli.serialize(verified)));
            return new CatalogueCheckpoint(1,verified.realm(),verified.databaseEpoch(),previous==null?1:Math.addExact(previous.sequence(),1),
                    verified.intentDigests().size(),digest,previous==null?"":previous.digest(),now.toString());
        }catch(Exception ignored){throw new IllegalStateException("CHECKPOINT_ADVANCE_REJECTED");}
    }
}
