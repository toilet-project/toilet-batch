import com.example.toiletbatch.account.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Linux-only standalone synthetic filesystem checks. No Spring, DB, R2 or network clients. */
class RetentionLinuxRehearsal {
    static final String ID="01234567-1234-1234-1234-123456789012";
    static final String OLD="toilet-db-20260101-000000.sql.gz.enc", FRESH="toilet-db-20260102-000000.sql.gz.enc";
    static Path root,wrapper,tools;
    static int passed;
    record Result(int code,String output) { }
    record Fixture(Path directory,Path backups,Path state,String protectedHash) { }
    static void check(boolean condition,String code) { if(!condition)throw new IllegalStateException(code); }
    static void pass(String name){passed++;System.out.println("PASS "+name);}
    static Path privateDirectory(Path directory)throws Exception {
        return Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String backup(Path directory,String name,Instant end)throws Exception{
        byte[] bytes=("SYNTHETIC_NOT_A_DATABASE_BACKUP:"+name).getBytes(StandardCharsets.UTF_8);
        String digest=hash(bytes);Path file=directory.resolve(name);
        Files.write(file,bytes);Files.setLastModifiedTime(file,FileTime.from(end));
        Files.writeString(directory.resolve(name+".sha256"),digest+"  "+name);
        var metadata=new BackupCaptureMetadata(1,name,digest,bytes.length,end.minusSeconds(60).toString(),end.toString(),"toilet_db",ID,ID);
        Files.write(directory.resolve(name+".metadata.json"),new ObjectMapper().writeValueAsBytes(metadata));return digest;
    }
    static Fixture fixture(String name)throws Exception {
        Path directory=privateDirectory(root.resolve(name));Path backups=privateDirectory(directory.resolve("backups"));
        Path state=privateDirectory(directory.resolve("state"));
        backup(backups,OLD,Instant.now().minus(Duration.ofDays(15)));
        String hash=backup(backups,FRESH,Instant.now().minusSeconds(3600));
        return new Fixture(directory,backups,state,hash);
    }
    static Map<String,String> environment(Fixture f){
        var env=new HashMap<String,String>();env.put("PATH","/usr/bin:/bin");env.put("LANG","C.UTF-8");
        env.put("GEUPDDONG_BACKUP_DIR",f.backups().toString());env.put("GEUPDDONG_RETENTION_STATE_DIR",f.state().toString());
        env.put("GEUPDDONG_ERASURE_TOOL_DIR",tools.toString());env.put("GEUPDDONG_MYSQL_SERVER_UUID",ID);
        env.put("GEUPDDONG_DATABASE_EPOCH",ID);env.put("GEUPDDONG_RESTORE_VERIFIED_BACKUP_SHA256",f.protectedHash());
        env.put("GEUPDDONG_RETENTION_NOTIFICATIONS_ENABLED","false");return env;
    }
    static Result run(Fixture f,boolean direct,Map<String,String> changes,String...args)throws Exception{
        var command=new ArrayList<String>();
        if(direct)command.addAll(List.of("/usr/bin/java","-cp",tools.resolve("lib/*").toString(),"com.example.toiletbatch.account.BackupRetentionCli"));
        else command.addAll(List.of("/bin/bash",wrapper.toString()));
        command.addAll(List.of(args));
        Path output=Files.createTempFile(f.directory(),"process-",".log");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();builder.environment().putAll(environment(f));builder.environment().putAll(changes);
        var process=builder.start();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("PROCESS_TIMEOUT");}
        String text=Files.readString(output);check(text.length()<8192,"UNEXPECTED_OUTPUT");return new Result(process.exitValue(),text);
    }
    static Map<String,String> authorize(String digest){return Map.of("GEUPDDONG_APPROVED_RETENTION_PLAN_SHA256",digest,"GEUPDDONG_RESTORE_AND_WRITERS_STOPPED","true");}
    static BackupRetentionMaintenance.Context context(Fixture f){return new BackupRetentionMaintenance.Context(ID,ID,f.protectedHash());}
    static String digest(Fixture f){var instant=Instant.now();return new BackupRetentionMaintenance().plan(BackupEvidenceInventory.scan(f.backups(),instant),instant,context(f)).digest();}
    static void tests()throws Exception{
        var f=fixture("dry-run");var result=run(f,false,Map.of(),"--dry-run");
        check(result.code()==2&&result.output().contains("status=READY"),"DRY_RUN_STATUS");
        check(Files.exists(f.backups().resolve(OLD))&&Files.exists(f.backups().resolve(FRESH)),"DRY_RUN_DELETED");pass("dry-run-preserves-all-files");

        try(var stream=Files.list(f.state())){check(stream.findAny().isEmpty(),"DRY_RUN_WROTE_STATE");}
        check(Files.getPosixFilePermissions(f.state()).equals(PosixFilePermissions.fromString("rwx------")),"STATE_PERMISSIONS");pass("private-state-no-disabled-notice-writes");

        result=run(f,true,Map.of(),"--dry-run");check(result.code()==1&&result.output().contains("preflight"),"LOCK_ATTESTATION");pass("direct-cli-requires-lock-attestation");
        Files.setPosixFilePermissions(f.state(),PosixFilePermissions.fromString("rwxr-x---"));
        result=run(f,true,Map.of("GEUPDDONG_RETENTION_LOCK_HELD","true"),"--dry-run");
        check(result.code()==1&&result.output().contains("preflight"),"LOOSE_STATE_PERMISSIONS");
        Files.setPosixFilePermissions(f.state(),PosixFilePermissions.fromString("rwx------"));pass("linux-posix-permissions-rejected");

        var lockBuilder=new ProcessBuilder("/bin/bash","-c","exec 9>\"$1\"; flock -x 9; printf 'LOCKED\\n'; read -r release","rehearsal",f.backups().resolve(".backup.lock").toString());
        var lock=lockBuilder.start();
        try {
            check(new java.io.BufferedReader(new java.io.InputStreamReader(lock.getInputStream())).readLine().equals("LOCKED"),"LOCK_HOLDER_FAILED");
            result=run(f,false,Map.of(),"--dry-run");check(result.code()==1&&result.output().contains("LOCK_BUSY"),"LOCK_NOT_ENFORCED");
        } finally {lock.getOutputStream().close();if(!lock.waitFor(5,TimeUnit.SECONDS))lock.destroyForcibly();}
        check(Files.exists(f.backups().resolve(OLD)),"LOCKED_OPERATION_DELETED");pass("real-flock-excludes-second-process");

        Files.writeString(f.backups().resolve(".backup.lock"),"SYNTHETIC_LOCK_ANOMALY");
        result=run(f,false,Map.of(),"--dry-run");
        check(result.code()==1&&Files.readString(f.backups().resolve(".backup.lock")).equals("SYNTHETIC_LOCK_ANOMALY"),"LOCK_ANOMALY_TRUNCATED");
        Files.writeString(f.backups().resolve(".backup.lock"),"");pass("nonempty-lock-preserved-and-rejected");

        var fifo=fixture("fifo-lock");
        var createFifo=new ProcessBuilder("/usr/bin/mkfifo",fifo.backups().resolve(".backup.lock").toString()).start();
        check(createFifo.waitFor(5,TimeUnit.SECONDS)&&createFifo.exitValue()==0,"FIFO_FIXTURE_FAILED");
        result=run(fifo,false,Map.of(),"--dry-run");check(result.code()==1,"FIFO_LOCK_NOT_REJECTED");pass("nonregular-lock-rejected-without-blocking");

        result=run(f,false,authorize("0".repeat(64)),"--apply");check(result.code()==1&&Files.exists(f.backups().resolve(OLD)),"BAD_APPROVAL");pass("wrong-plan-cannot-delete");
        result=run(f,false,Map.of("GEUPDDONG_APPROVED_RETENTION_PLAN_SHA256",digest(f)),"--apply");
        check(result.code()==1&&Files.exists(f.backups().resolve(OLD)),"WRITER_EXCLUSION");pass("apply-requires-writers-stopped");

        result=run(f,false,authorize(digest(f)),"--apply");
        check(result.code()==0&&result.output().contains("removedBackups=1"),"APPLY_FAILED");
        check(!Files.exists(f.backups().resolve(OLD))&&Files.exists(f.backups().resolve(FRESH)),"WRONG_FILES_REMOVED");
        List<Path> journals;try(var stream=Files.list(f.state())){journals=stream.filter(p->p.toString().endsWith(".journal")).toList();}
        check(journals.size()==1&&Files.readString(journals.getFirst()).endsWith("COMPLETE\n"),"JOURNAL_INCOMPLETE");
        check(Files.getPosixFilePermissions(journals.getFirst()).equals(PosixFilePermissions.fromString("rw-------")),"JOURNAL_PERMISSIONS");pass("approved-synthetic-cleanup-private-durable-journal");

        var legacy=fixture("legacy");Files.delete(legacy.backups().resolve(OLD+".metadata.json"));
        result=run(legacy,false,Map.of(),"--dry-run");check(result.code()==2&&result.output().contains("HOLD_LEGACY_METADATA"),"LEGACY_NOT_HELD");pass("legacy-backup-held");
        var mismatch=fixture("identity");result=run(mismatch,false,Map.of("GEUPDDONG_DATABASE_EPOCH","01234567-1234-1234-1234-123456789013"),"--dry-run");
        check(result.code()==2&&result.output().contains("HOLD_IDENTITY"),"EPOCH_NOT_HELD");pass("restore-generation-mismatch-held");
        var corrupt=fixture("corrupt");Files.writeString(corrupt.backups().resolve(OLD+".sha256"),"invalid");
        result=run(corrupt,false,Map.of(),"--dry-run");check(result.code()==1&&result.output().contains("scan"),"CORRUPT_NOT_FAILED");pass("checksum-corruption-fails-closed");

        var linked=fixture("symlink");Path target=linked.directory().resolve("outside.txt");Files.writeString(target,"untouched");
        Files.createSymbolicLink(linked.backups().resolve("unknown-link"),target);
        result=run(linked,false,Map.of(),"--dry-run");check(result.code()==2&&result.output().contains("HOLD_UNKNOWN_FILES")&&Files.readString(target).equals("untouched"),"SYMLINK_NOT_HELD");pass("symlink-never-followed");
        Path stateLink=linked.directory().resolve("state-link");Files.createSymbolicLink(stateLink,linked.state());
        result=run(linked,false,Map.of("GEUPDDONG_RETENTION_STATE_DIR",stateLink.toString()),"--dry-run");check(result.code()!=0,"STATE_LINK_ACCEPTED");pass("state-symlink-rejected");

        var partial=fixture("partial");Path journal=partial.state().resolve("partial.journal");
        try{
            new BackupRetentionMaintenance().apply(partial.backups(),context(partial),digest(partial),journal,Clock.systemUTC(),true,path->{
                if(path.toString().endsWith(".sha256"))throw new java.io.IOException("SYNTHETIC_FAILURE");Files.delete(path);
            });throw new IllegalStateException("EXPECTED_PARTIAL_FAILURE");
        }catch(java.io.IOException expected){check(Files.readString(journal).contains("INCOMPLETE_REVIEW_REQUIRED"),"MISSING_FAILURE_JOURNAL");}
        result=run(partial,false,Map.of(),"--dry-run");check(result.code()==2&&result.output().contains("HOLD_UNKNOWN_FILES"),"PARTIAL_NOT_HELD");pass("partial-deletion-journal-and-followup-hold");

        var notice=fixture("notice");
        result=run(notice,false,Map.of("GEUPDDONG_RETENTION_NOTIFICATIONS_ENABLED","true","BATCH_FAILURE_WEBHOOK_URL","https://invalid.example/not-a-webhook"),"--dry-run");
        check(result.code()==1&&result.output().contains("notification")&&!result.output().contains("invalid.example"),"NOTICE_NOT_FAILED");
        check(!Files.exists(notice.state().resolve("notice-state.json")),"FAILED_NOTICE_ACKNOWLEDGED");pass("invalid-webhook-fails-without-secret-or-acknowledgement");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("THREE_PATHS_REQUIRED");
        root=Path.of(args[0]).toAbsolutePath().normalize();wrapper=Path.of(args[1]).toAbsolutePath().normalize();tools=Path.of(args[2]).toAbsolutePath().normalize();
        check(root.toString().startsWith("/tmp/geupddong-retention-check.")&&root.equals(root.toRealPath()),"ISOLATED_ROOT_REQUIRED");
        check(Files.readString(root.resolve("SYNTHETIC_ONLY")).equals("retention-rehearsal-v1\n"),"ISOLATION_MARKER_REQUIRED");
        check(Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")),"ROOT_PERMISSIONS");
        check(wrapper.startsWith(root)&&tools.startsWith(root),"TOOLS_MUST_BE_ISOLATED");
        tests();System.out.println("LINUX_RETENTION_REHEARSAL_OK tests="+passed+" realBackupsTouched=0 networkRequests=0");
    }
}
