package com.example.toiletbatch.account;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Run only through the flock wrapper. No DB or R2 clients; deletion requires a matching approved plan. */
public final class BackupRetentionCli {
    private BackupRetentionCli(){ }
    public static void main(String[] args){
        String stage="preflight";
        try{
            boolean apply=args.length==1 && "--apply".equals(args[0]);
            if(args.length>1 || (args.length==1 && !apply && !"--dry-run".equals(args[0])))throw new IllegalArgumentException();
            if(!"true".equals(required("GEUPDDONG_RETENTION_LOCK_HELD")))throw new IllegalStateException();
            Path root=Path.of(required("GEUPDDONG_BACKUP_DIR"));
            Path state=Path.of(required("GEUPDDONG_RETENTION_STATE_DIR")).toAbsolutePath().normalize();
            // The wrapper creates mode 0700 before launching. Do not downgrade on unsupported platforms.
            if(!state.equals(state.toRealPath()) || state.startsWith(root.toAbsolutePath().normalize())
                    || Files.getPosixFilePermissions(state).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalStateException();
            var context=new BackupRetentionMaintenance.Context(required("GEUPDDONG_MYSQL_SERVER_UUID"),
                    required("GEUPDDONG_DATABASE_EPOCH"),required("GEUPDDONG_RESTORE_VERIFIED_BACKUP_SHA256"));
            var clock=Clock.systemUTC();var maintenance=new BackupRetentionMaintenance();
            stage="scan";
            BackupRetentionMaintenance.Plan plan;
            try{plan=maintenance.plan(BackupEvidenceInventory.scan(root,clock.instant()),clock.instant(),context);}
            catch(RuntimeException invalid){
                // A malformed inventory is actionable, not a successful empty scan.
                notifyIfEnabled(state,BackupRetentionMaintenance.Status.HOLD_UNKNOWN_FILES,0,clock);
                throw invalid;
            }
            System.out.printf("dryRun=%s status=%s expired=%d planSha256=%s%n",!apply,plan.status(),plan.expired().size(),plan.digest());
            if(apply){
                stage="apply";
                if(!"true".equals(required("GEUPDDONG_RESTORE_AND_WRITERS_STOPPED")))throw new IllegalStateException();
                try{
                    int removed=maintenance.apply(root,context,required("GEUPDDONG_APPROVED_RETENTION_PLAN_SHA256"),
                            state.resolve("cleanup-"+UUID.randomUUID()+".journal"),clock,true,Files::delete);
                    System.out.printf("removedBackups=%d%n",removed);
                }catch(Exception failure){
                    notifyIfEnabled(state,BackupRetentionMaintenance.Status.HOLD_UNKNOWN_FILES,plan.expired().size(),clock);
                    throw failure;
                }
                plan=maintenance.plan(BackupEvidenceInventory.scan(root,clock.instant()),clock.instant(),context);
            }
            stage="notification";
            notifyIfEnabled(state,plan.status(),plan.expired().size(),clock);
            if(plan.status()!=BackupRetentionMaintenance.Status.NO_EXPIRED)System.exit(2);
        }catch(Exception ignored){System.err.println("BACKUP_RETENTION_FAILED: "+stage);System.exit(1);}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException();return value;}
    private static void notifyIfEnabled(Path directory,BackupRetentionMaintenance.Status status,int count,Clock clock)throws Exception{
        if(!"true".equals(System.getenv("GEUPDDONG_RETENTION_NOTIFICATIONS_ENABLED")))return;
        Path target=directory.resolve("notice-state.json");var json=new ObjectMapper();
        var store=new RetentionFailureNotice.Store(){
            public RetentionFailureNotice.State read()throws Exception{
                if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return null;
                byte[] bytes;try(var in=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(1025);}
                if(bytes.length>1024)throw new IllegalStateException();return json.readValue(bytes,RetentionFailureNotice.State.class);
            }
            public void write(RetentionFailureNotice.State state)throws Exception{
                Path temporary=Files.createTempFile(directory,"notice-",".tmp");
                try{
                    Files.write(temporary,json.writeValueAsBytes(state));
                    Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(temporary);}
            }
        };
        new RetentionFailureNotice(store,RetentionFailureNotice.discord(required("BATCH_FAILURE_WEBHOOK_URL"))).notify(status,count,clock.instant());
    }
}
