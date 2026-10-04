package com.geupddong.account;

import java.time.Clock;

/** Dedicated orphan branch. Account main/history are never changed by a review request. */
public final class ReviewCheckpointStore {
    public static final String BRANCH="review-anonymization-v1";
    private ReviewCheckpointStore(){}
    public static CheckpointedErasureLedger.Store configured(String token){
        return new GitHubErasureHistoryStore(scoped(GitHubErasureCheckpointStore.configuredTransport(token)),Clock.systemUTC());
    }
    static GitHubErasureCheckpointStore.Transport scoped(GitHubErasureCheckpointStore.Transport transport){
        return (method,path,body)->{
            if(path.equals("/git/ref/heads/main"))path="/git/ref/heads/"+BRANCH;
            else if(path.equals("/git/refs/heads/main"))path="/git/refs/heads/"+BRANCH;
            else if(path.contains("/heads/") || path.equals("/git/refs"))throw new IllegalStateException("REVIEW_CHECKPOINT_SCOPE_INVALID");
            return transport.request(method,path,body);
        };
    }
}
