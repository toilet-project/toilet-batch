package com.geupddong.growth;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

/** Replays growth cleanup after an offline review unlink, inside the caller's transaction. */
public final class GrowthUnlinkRestore {
    private static final Set<String> TABLES=Set.of("growth_policy_snapshot","growth_policy_target",
            "growth_account","growth_award","growth_xp_event","growth_review_evidence",
            "growth_review_exclusion");

    private GrowthUnlinkRestore() { }

    public static void reconcileDetached(JdbcTemplate jdbc, Collection<String> reviewKeys) {
        Set<String> present=presentTables(jdbc);
        if(present.isEmpty()) return; // Old backup predates member growth.
        if(!present.containsAll(TABLES))
            throw new IllegalStateException("성장 보상 테이블이 일부만 존재해 리뷰 연결 해제를 중단했습니다.");
        if(reviewKeys==null || reviewKeys.isEmpty()) return;
        Set<Long> affected=new LinkedHashSet<>();
        for(String key:new HashSet<>(reviewKeys)) {
            if(key==null || !key.matches("[0-9a-fA-F-]{36}"))
                throw new IllegalArgumentException("리뷰 연결 키가 올바르지 않습니다.");
            affected.addAll(jdbc.query("SELECT user_id FROM growth_review_evidence WHERE review_key=?",
                    (rs,i)->rs.getLong(1),key));
        }
        if(affected.isEmpty()) return;
        List<String> versions=jdbc.query("SELECT policy_version FROM growth_policy_snapshot ORDER BY initialized_at DESC",
                (rs,i)->rs.getString(1));
        if(versions.isEmpty()) throw new IllegalStateException("성장 보상 기준이 없어 익명화 정산을 중단했습니다.");
        GrowthLedger.Policy policy=GrowthLedger.policy(jdbc,versions.getFirst());
        for(Long userId:affected) {
            List<Long> locked=jdbc.query("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",
                    (rs,i)->rs.getLong(1),userId);
            if(locked.isEmpty()) throw new IllegalStateException("성장 보상 대상 회원이 없습니다.");
            GrowthLedger.reconcile(jdbc,userId,policy,"REVIEW_UNLINK_RESTORE",Clock.systemUTC(),false);
        }
    }

    private static Set<String> presentTables(JdbcTemplate jdbc) {
        return jdbc.execute((ConnectionCallback<Set<String>>) connection->{
            DatabaseMetaData meta=connection.getMetaData();
            Set<String> present=new HashSet<>();
            try(ResultSet tables=meta.getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {
                while(tables.next()) {
                    String name=tables.getString("TABLE_NAME");
                    if(name!=null && TABLES.contains(name.toLowerCase(java.util.Locale.ROOT)))
                        present.add(name.toLowerCase(java.util.Locale.ROOT));
                }
            }
            return present;
        });
    }
}
