package com.geupddong.account;

import com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** DB facts are SELECTed live; all-copy clearance must come from an explicit authenticated audit.
 * Never infer no copies from an empty dump folder or from retention settings alone.
 */
final class RetirementOperationalEvidence implements RetirementExecutionContext.EvidenceSource {
    static final Set<String> SOURCES=Set.of("DUMPS","BINLOGS","REDIS_DISK","LEGACY_VOLUMES","RESTORE_TEMP","OFFHOST_COPIES");
    record Audit(int version,String realm,String epoch,String serverUuid,String checkedAt,String policyVersion,
                 String scopeDigest,String keyRecoveryCheckedAt,String lastRestoreAt,Map<String,String> clearedThrough) {
        Audit {
            try {
                if(version!=1 || realm==null || !realm.matches("[a-z0-9-]{3,40}") || !UUID.fromString(epoch).toString().equals(epoch)
                        || !UUID.fromString(serverUuid).toString().equals(serverUuid) || !"LOCAL_RETENTION_V1".equals(policyVersion)
                        || scopeDigest==null || !scopeDigest.matches("[a-f0-9]{64}") || !clearedThrough.keySet().equals(SOURCES))throw invalid();
                var at=Instant.parse(checkedAt);Instant.parse(keyRecoveryCheckedAt);Instant.parse(lastRestoreAt);
                clearedThrough=Collections.unmodifiableMap(new TreeMap<>(clearedThrough));
                for(String time:clearedThrough.values())if(Instant.parse(time).isAfter(at))throw invalid();
            }catch(RuntimeException ignored){throw invalid();}
        }
        @Override public String toString(){return "RetirementAudit[redacted]";}
    }
    interface AuditSource {Audit read();}
    private final JdbcTemplate jdbc;private final String realm,epoch,serverUuid,scopeDigest;
    private final AuditSource audits;private final Clock clock;private final Runnable requireLease;
    RetirementOperationalEvidence(JdbcTemplate jdbc,String realm,String epoch,String serverUuid,String scopeDigest,
                                  AuditSource audits,Clock clock,Runnable requireLease) {
        this.jdbc=jdbc;this.realm=realm;this.epoch=epoch;this.serverUuid=serverUuid;this.scopeDigest=scopeDigest;
        this.audits=audits;this.clock=clock;this.requireLease=requireLease;
    }
    @Override public Evidence current(ErasureRecord record,ErasureCompletion completion) {
        try {
            requireLease.run();Instant now=clock.instant(),absent=Instant.parse(completion.firstConfirmedAbsentAt());Audit audit=audits.read();
            if(!realm.equals(record.realm()) || !realm.equals(completion.realm()) || !epoch.equals(completion.databaseEpoch())
                    || !completion.intentDigest().equals(ErasureCompletion.digest(record)) || !completion.withdrawalKey().equals(record.withdrawalKey())
                    || !realm.equals(audit.realm()) || !epoch.equals(audit.epoch()) || !serverUuid.equals(audit.serverUuid())
                    || !scopeDigest.equals(audit.scopeDigest()))throw invalid();
            Instant checked=Instant.parse(audit.checkedAt()),keyChecked=Instant.parse(audit.keyRecoveryCheckedAt());
            if(checked.isAfter(now) || now.isAfter(checked.plusSeconds(600)) || keyChecked.isAfter(now)
                    || now.isAfter(keyChecked.plusSeconds(86400)) || Instant.parse(audit.lastRestoreAt()).isAfter(absent))throw invalid();
            if(!serverUuid.equals(jdbc.queryForObject("SELECT @@server_uuid",String.class))
                    || !"toilet_db".equals(jdbc.queryForObject("SELECT DATABASE()",String.class)))throw invalid();
            var rows=jdbc.query("SELECT created_at FROM app_user WHERE user_id=?",(rs,n)->rs.getObject(1,LocalDateTime.class),record.userId());
            if(!rows.isEmpty())throw invalid(); // Includes an ID reused for a different account, not just the original identity.
            var verified=EnumSet.allOf(Requirement.class);
            for(String cutoff:audit.clearedThrough().values())if(Instant.parse(cutoff).isBefore(absent))verified.remove(Requirement.PRE_ERASURE_COPIES_AND_LOGS_REMOVED);
            return new Evidence(absent,now,verified);
        }catch(Exception ignored){throw invalid();}
    }
    /** The audit is provisioned by a separately approved inventory run, not self-certified by this CLI. */
    static AuditSource authenticatedFile(Path path,String realm,String epoch,ErasureCipher cipher,FileErasureObjectStore.Safety safety) {
        return ()->{
            try {
                if(!path.isAbsolute() || !path.equals(path.normalize()) || !path.getFileName().toString().equals("retirement-audit.bin"))throw invalid();
                for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw invalid();
                if(!path.toRealPath().equals(path))throw invalid();safety.directory(path.getParent());safety.file(path.getParent(),path);
                byte[] bytes;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(8193);}
                if(bytes.length>8192)throw invalid();byte[] plain=cipher.decryptDocument(realm,"retirement-audit-v1/"+realm+"/"+epoch,bytes);
                var json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                Audit audit=json.readValue(plain,Audit.class);
                if(!Arrays.equals(plain,json.writeValueAsBytes(audit)))throw invalid();return audit;
            }catch(Exception ignored){throw invalid();}
        };
    }
    private static IllegalStateException invalid(){return new IllegalStateException("RETIREMENT_OPERATIONAL_EVIDENCE_UNAVAILABLE");}
}
