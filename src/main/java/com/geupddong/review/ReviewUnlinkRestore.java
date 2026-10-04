package com.geupddong.review;

import com.geupddong.growth.GrowthUnlinkRestore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline restore only. Does not delete members, reviews, reports, operational logs or timestamps. */
public final class ReviewUnlinkRestore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public ReviewUnlinkRestore(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);
        // Re-evaluate current valid contributions after each owner lock, never a pre-lock RR snapshot.
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public record Result(int records,int matched,int absent,int unlinked){}
    public Result replay(ReviewUnlinkJournal.Snapshot snapshot,boolean apply){
        return tx.execute(status->{
            var keys=new TreeSet<String>();
            for(var record:snapshot.records())if(!keys.add(record.reviewKey()))throw new IllegalStateException("REVIEW_UNLINK_RESTORE_INVALID");
            var ids=new ArrayList<Long>();int absent=0;
            for(String key:keys){
                var rows=jdbc.query("SELECT review_id FROM toilet_review WHERE review_key=?"+(apply?" FOR UPDATE":""),(rs,n)->rs.getLong(1),key);
                if(rows.isEmpty())absent++;else if(rows.size()!=1)throw new IllegalStateException("REVIEW_UNLINK_RESTORE_IDENTITY_CONFLICT");else ids.add(rows.getFirst());
            }
            int changed=0;
            if(apply)for(long id:ids){
                changed+=jdbc.update("UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE,version=version+1 WHERE review_id=? AND (author_user_id IS NOT NULL OR author_detached=FALSE)",id);
                jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=?",id);
            }
            // Replay every key, including already-unlinked/absent reviews, so a newer backup cannot
            // retain a reward source that identifies the author of a previously anonymous review.
            if(apply)GrowthUnlinkRestore.reconcileDetached(jdbc,keys);
            return new Result(keys.size(),ids.size(),absent,changed);
        });
    }
}
