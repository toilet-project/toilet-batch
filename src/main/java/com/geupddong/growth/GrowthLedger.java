package com.geupddong.growth;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** JDBC-only calculation shared with offline backup replay. No web, auth or policy-service dependency. */
public final class GrowthLedger {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private GrowthLedger() { }

    public record Target(String code, String sidoCode, String sidoName, String displayName) { }
    public record Rules(String version, int reviewXp, int checkinXp, int districtXp,
                        int bronzeXp, int silverXp, int goldXp, int bronzeFacilities,
                        int silverFacilities, int goldFacilities, int silverPercent, int goldPercent) { }
    public record Policy(Rules rules, List<Target> targets) { }
    public record Review(String key, long toiletId, String sigunguCode) { }
    public record Award(long id, String kind, String key, String version, int amount,
                        boolean active, LocalDateTime awardedAt) { }
    public record AwardSpec(String kind, String key, String version, int amount) {
        public String identity() { return kind + ":" + key; }
    }
    public record Projection(Map<String, AwardSpec> awards, List<Review> reviews,
                             int distinctFacilities, int pendingRegionFacilities,
                             Map<String, Integer> provinceFacilities,
                             Map<String, Set<String>> provinceDistricts) { }
    public record Result(long userId, int earnedCount, int revokedCount, long totalXp) { }
    public record Forecast(Projection projection, long expectedXp, int expectedAwards) { }

    public static List<Target> currentTargets(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT DISTINCT ref.sigungu_code,ref.sido_code,ref.sido_name,ref.display_name
                FROM current_toilet_region r
                JOIN toilet t ON t.toilet_id=r.toilet_id
                LEFT JOIN region_sigungu_reference source_ref ON source_ref.sigungu_code=r.sigungu_code
                JOIN region_sigungu_reference ref ON (
                    (source_ref.sigungu_code IS NOT NULL AND ref.sido_code=
                        CASE source_ref.sido_code WHEN '29' THEN '12' WHEN '46' THEN '12'
                             ELSE source_ref.sido_code END
                     AND (ref.sigungu_name=source_ref.sigungu_name OR
                          (ref.sigungu_name IS NULL AND source_ref.sigungu_name IS NULL)))
                    OR (source_ref.sigungu_code IS NULL AND
                        ((LEFT(r.sigungu_code,2)='42' AND ref.sigungu_code=CONCAT('51',SUBSTRING(r.sigungu_code,3)))
                         OR (LEFT(r.sigungu_code,2)='45' AND ref.sigungu_code=CONCAT('52',SUBSTRING(r.sigungu_code,3))))))
                    AND LEFT(ref.sigungu_code,2)=ref.sido_code
                WHERE r.status='VERIFIED' AND t.visibility_status='VISIBLE'
                  AND (source_ref.is_active=TRUE OR
                       (source_ref.sigungu_code IS NULL AND LEFT(r.sigungu_code,2) IN ('42','45')))
                  AND ref.is_active=TRUE
                ORDER BY ref.sido_code,ref.sigungu_code
                """, (rs, i) -> new Target(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)));
    }

    public static Policy policy(JdbcTemplate jdbc, String version) {
        Rules rules = jdbc.query("""
                SELECT policy_version,review_xp,checkin_xp,district_xp,bronze_xp,silver_xp,gold_xp,
                       bronze_facilities,silver_facilities,gold_facilities,silver_coverage_percent,gold_coverage_percent
                FROM growth_policy_snapshot WHERE policy_version=?
                """, (rs, i) -> new Rules(rs.getString(1),rs.getInt(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),
                rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getInt(9),rs.getInt(10),rs.getInt(11),rs.getInt(12)),
                version).stream().findFirst().orElse(null);
        if (rules == null) return null;
        List<Target> targets = jdbc.query("""
                SELECT sigungu_code,sido_code,sido_name,display_name
                FROM growth_policy_target WHERE policy_version=? ORDER BY sido_code,sigungu_code
                """, (rs, i) -> new Target(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),version);
        return new Policy(rules,List.copyOf(targets));
    }

    public static List<Review> eligibleReviews(JdbcTemplate jdbc,long userId) {
        return jdbc.query("""
                SELECT r.review_key,r.toilet_id,canonical.sigungu_code
                FROM toilet_review r
                JOIN toilet t ON t.toilet_id=r.toilet_id AND t.visibility_status='VISIBLE'
                LEFT JOIN current_toilet_region rg ON rg.toilet_id=r.toilet_id
                    AND rg.status='VERIFIED'
                LEFT JOIN region_sigungu_reference source_ref ON source_ref.sigungu_code=rg.sigungu_code
                LEFT JOIN region_sigungu_reference canonical ON (
                    (source_ref.is_active=TRUE AND canonical.sido_code=
                        CASE source_ref.sido_code WHEN '29' THEN '12' WHEN '46' THEN '12'
                             ELSE source_ref.sido_code END
                     AND (canonical.sigungu_name=source_ref.sigungu_name OR
                          (canonical.sigungu_name IS NULL AND source_ref.sigungu_name IS NULL)))
                    OR (source_ref.sigungu_code IS NULL AND
                        ((LEFT(rg.sigungu_code,2)='42' AND canonical.sigungu_code=CONCAT('51',SUBSTRING(rg.sigungu_code,3)))
                         OR (LEFT(rg.sigungu_code,2)='45' AND canonical.sigungu_code=CONCAT('52',SUBSTRING(rg.sigungu_code,3))))))
                    AND LEFT(canonical.sigungu_code,2)=canonical.sido_code
                    AND canonical.is_active=TRUE
                LEFT JOIN growth_review_exclusion x ON x.review_id=r.review_id
                WHERE r.author_user_id=? AND r.author_detached=FALSE AND x.review_id IS NULL
                  AND EXISTS (SELECT 1 FROM toilet_review_submission s
                              WHERE s.review_id=r.review_id AND s.user_id=r.author_user_id)
                  AND r.satisfaction BETWEEN 1 AND 5 AND r.cleanliness BETWEEN 1 AND 5
                  AND r.paper_available IS NOT NULL
                ORDER BY r.created_at,r.review_id
                """, (rs, i) -> new Review(rs.getString(1),rs.getLong(2),rs.getString(3)),userId);
    }

    public static Projection project(JdbcTemplate jdbc,long userId,Policy policy) {
        List<Review> reviews=eligibleReviews(jdbc,userId);
        return project(reviews,policy);
    }

    public static Projection project(List<Review> reviews,Policy policy) {
        Map<String,Target> targetByCode=new HashMap<>();
        Map<String,Integer> targetCounts=new HashMap<>();
        for(Target target:policy.targets()) {
            targetByCode.put(target.code(),target);
            targetCounts.merge(target.sidoCode(),1,Integer::sum);
        }
        Map<Long,Review> firstByToilet=new LinkedHashMap<>();
        for(Review review:reviews) firstByToilet.putIfAbsent(review.toiletId(),review);
        Map<String,AwardSpec> desired=new LinkedHashMap<>();
        Map<String,Integer> facilities=new HashMap<>();
        Map<String,Set<String>> districts=new HashMap<>();
        int pending=0;
        Rules r=policy.rules();
        for(Review review:firstByToilet.values()) {
            add(desired,new AwardSpec("REVIEW_FIRST","T:"+review.toiletId(),r.version(),r.reviewXp()));
            Target target=targetByCode.get(review.sigunguCode());
            if(target==null) { pending++; continue; }
            facilities.merge(target.sidoCode(),1,Integer::sum);
            districts.computeIfAbsent(target.sidoCode(),ignored->new HashSet<>()).add(target.code());
        }
        for(Target target:policy.targets()) {
            if(districts.getOrDefault(target.sidoCode(),Set.of()).contains(target.code()))
                add(desired,new AwardSpec("DISTRICT","D:"+target.code(),r.version(),r.districtXp()));
        }
        for(var entry:targetCounts.entrySet()) {
            String sido=entry.getKey(); int targetCount=entry.getValue();
            int facilityCount=facilities.getOrDefault(sido,0);
            int districtCount=districts.getOrDefault(sido,Set.of()).size();
            if(facilityCount>=r.bronzeFacilities() && districtCount>=1)
                add(desired,new AwardSpec("MEDAL_BRONZE","P:"+sido,r.version(),r.bronzeXp()));
            if(facilityCount>=r.silverFacilities() && districtCount>=ceilPercent(targetCount,r.silverPercent()))
                add(desired,new AwardSpec("MEDAL_SILVER","P:"+sido,r.version(),r.silverXp()));
            if(facilityCount>=r.goldFacilities() && districtCount>=ceilPercent(targetCount,r.goldPercent()))
                add(desired,new AwardSpec("MEDAL_GOLD","P:"+sido,r.version(),r.goldXp()));
        }
        return new Projection(Map.copyOf(desired),List.copyOf(reviews),firstByToilet.size(),pending,
                Map.copyOf(facilities),Map.copyOf(districts));
    }

    public static int ceilPercent(int count,int percent) {
        return (count*percent+99)/100;
    }

    private static void add(Map<String,AwardSpec> desired,AwardSpec award) {
        desired.put(award.identity(),award);
    }

    public static List<Award> awards(JdbcTemplate jdbc,long userId) {
        return jdbc.query("""
                SELECT award_id,award_kind,award_key,policy_version,xp_amount,active,awarded_at
                FROM growth_award WHERE user_id=? ORDER BY award_id
                """,(rs,i)->new Award(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getInt(5),rs.getBoolean(6),rs.getObject(7,LocalDateTime.class)),userId);
    }

    public static long totalXp(JdbcTemplate jdbc,long userId) {
        Long total=jdbc.query("SELECT total_xp FROM growth_account WHERE user_id=?",
                (rs,i)->rs.getLong(1),userId).stream().findFirst().orElse(null);
        return total==null?0:total;
    }

    public static Result reconcile(JdbcTemplate jdbc,long userId,Policy policy,String reason,Clock clock) {
        return reconcile(jdbc,userId,policy,reason,clock,true);
    }

    /** Privacy cleanup may revoke stale awards without issuing any new award or source link. */
    public static Result reconcile(JdbcTemplate jdbc,long userId,Policy policy,String reason,Clock clock,
                                   boolean grantMissing) {
        Projection projection=project(jdbc,userId,policy);
        return reconcile(jdbc,userId,policy,projection,reason,clock,grantMissing);
    }

    public static Forecast forecast(JdbcTemplate jdbc,long userId,Policy policy) {
        Projection projection=project(jdbc,userId,policy);
        Map<String,AwardSpec> pending=new HashMap<>(projection.awards());
        Map<String,Projection> byVersion=new HashMap<>();
        byVersion.put(policy.rules().version(),projection);
        long expected=0;int count=0;
        for(Award award:awards(jdbc,userId)) {
            if(!award.active()) continue;
            if("CHECKIN".equals(award.kind())) {expected+=award.amount();count++;continue;}
            if(validOldAward(jdbc,award,policy,projection,byVersion)) {
                expected+=award.amount();count++;
                pending.remove(award.kind()+":"+award.key());
            }
        }
        for(AwardSpec spec:pending.values()) {expected+=spec.amount();count++;}
        return new Forecast(projection,expected,count);
    }

    private static boolean validOldAward(JdbcTemplate jdbc,Award award,Policy policy,Projection current,
                                         Map<String,Projection> byVersion) {
        if(award.key()==null) return false;
        Projection old=byVersion.get(award.version());
        if(old==null) {
            Policy oldPolicy=policy(jdbc,award.version());
            if(oldPolicy==null) throw new IllegalStateException("과거 성장 보상 기준을 찾을 수 없습니다.");
            old=project(current.reviews(),oldPolicy);
            byVersion.put(award.version(),old);
        }
        return old.awards().containsKey(award.kind()+":"+award.key());
    }

    private static Result reconcile(JdbcTemplate jdbc,long userId,Policy policy,Projection projection,
                                    String reason,Clock clock,boolean grantMissing) {
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),KST);
        Map<String,Award> active=new HashMap<>();
        for(Award award:awards(jdbc,userId)) if(award.active() && award.key()!=null)
            active.put(award.kind()+":"+award.key(),award);
        Map<String,Projection> byVersion=new HashMap<>();
        byVersion.put(policy.rules().version(),projection);
        int revoked=0,earned=0;
        List<String> revokedKeys=new ArrayList<>();
        for(var entry:active.entrySet()) {
            Award award=entry.getValue();
            if("CHECKIN".equals(award.kind()) || validOldAward(jdbc,award,policy,projection,byVersion)) continue;
            jdbc.update("""
                    UPDATE growth_award SET active=FALSE,award_key=NULL,revoked_at=?,scrubbed_at=?
                    WHERE award_id=? AND active=TRUE
                    """,now,now,award.id());
            jdbc.update("""
                    INSERT INTO growth_xp_event(user_id,award_id,delta_xp,event_kind,reason,happened_at)
                    VALUES(?,? ,?,'REVOKE',?,?)
                    """,userId,award.id(),-award.amount(),safeReason(reason),now);
            revokedKeys.add(entry.getKey());
            revoked++;
        }
        for(String key:revokedKeys) active.remove(key);
        if(grantMissing) {
            for(AwardSpec spec:projection.awards().values()) {
                if(active.containsKey(spec.identity())) continue;
                insertAward(jdbc,userId,spec,now,safeReason(reason));
                earned++;
            }
        }
        syncEvidence(jdbc,userId,projection.reviews(),now,grantMissing);
        long total=refreshBalance(jdbc,userId,now);
        return new Result(userId,earned,revoked,total);
    }

    public static void insertAward(JdbcTemplate jdbc,long userId,AwardSpec spec,LocalDateTime now,String reason) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("""
                    INSERT INTO growth_award(user_id,award_kind,award_key,policy_version,xp_amount,active,awarded_at)
                    VALUES(?,?,?,?,?,TRUE,?)
                    """,Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,userId);statement.setString(2,spec.kind());statement.setString(3,spec.key());
            statement.setString(4,spec.version());statement.setInt(5,spec.amount());statement.setObject(6,now);
            return statement;
        },keys);
        long awardId=keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO growth_xp_event(user_id,award_id,delta_xp,event_kind,reason,happened_at)
                VALUES(?, ?, ?,'EARN',?,?)
                """,userId,awardId,spec.amount(),safeReason(reason),now);
    }

    private static void syncEvidence(JdbcTemplate jdbc,long userId,List<Review> reviews,LocalDateTime now,
                                     boolean addMissing) {
        Set<String> desired=new HashSet<>();for(Review review:reviews) desired.add(review.key());
        List<String> existing=jdbc.query("SELECT review_key FROM growth_review_evidence WHERE user_id=?",
                (rs,i)->rs.getString(1),userId);
        for(String key:existing) if(!desired.contains(key))
            jdbc.update("DELETE FROM growth_review_evidence WHERE user_id=? AND review_key=?",userId,key);
        if(addMissing) {
            Set<String> present=new HashSet<>(existing);
            for(Review review:reviews) if(present.add(review.key()))
                jdbc.update("INSERT INTO growth_review_evidence(review_key,user_id,toilet_id,created_at) VALUES(?,?,?,?)",
                        review.key(),userId,review.toiletId(),now);
        }
    }

    public static long refreshBalance(JdbcTemplate jdbc,long userId,LocalDateTime now) {
        Long sum=jdbc.queryForObject("SELECT COALESCE(SUM(delta_xp),0) FROM growth_xp_event WHERE user_id=?",Long.class,userId);
        long total=sum==null?0:sum;
        if(total<0) throw new IllegalStateException("성장 보상 원장이 음수입니다.");
        int updated=jdbc.update("UPDATE growth_account SET total_xp=?,updated_at=? WHERE user_id=?",total,now,userId);
        if(updated==0) jdbc.update("INSERT INTO growth_account(user_id,total_xp,updated_at) VALUES(?,?,?)",userId,total,now);
        return total;
    }

    private static String safeReason(String reason) {
        if(reason==null || !reason.matches("[A-Z0-9_]{1,64}")) return "RECONCILE";
        return reason;
    }
}
