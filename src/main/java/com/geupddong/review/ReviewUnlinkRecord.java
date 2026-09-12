package com.geupddong.review;

import java.util.UUID;

/** Review incarnation only. Never an account erasure record; no member/facility/text/location data. */
public record ReviewUnlinkRecord(int version,String realm,String reviewKey) {
    public static final String REALM="review-anonymization";
    public ReviewUnlinkRecord {
        if(version!=1 || !REALM.equals(realm) || reviewKey==null || !UUID.fromString(reviewKey).toString().equals(reviewKey))
            throw new IllegalArgumentException("REVIEW_UNLINK_RECORD_INVALID");
    }
    public String key(){return "v1/"+realm+"/"+reviewKey+".bin";}
}
