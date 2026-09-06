package com.example.toiletbatch.account;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in shell fixture: mock Docker/flock/date/install, REAL gzip/OpenSSL/hash/publication. No real DB. */
class BackupCaptureScriptTest {
    @TempDir Path directory;
    private String bash;
    private Path script, bin, key, config;
    private String lastOutput;
    private static String unix(Path path) {
        String p=path.toAbsolutePath().toString().replace('\\','/');
        return p.matches("^[A-Za-z]:/.*") ? "/"+Character.toLowerCase(p.charAt(0))+p.substring(2) : p;
    }
    private void setup()throws Exception {
        bash=System.getenv("ERASURE_BACKUP_BASH");String source=System.getenv("ERASURE_BACKUP_SCRIPT");
        assumeTrue(bash!=null && source!=null,"Opt-in shell fixture paths required");
        script=directory.resolve("backup.sh");Files.writeString(script,Files.readString(Path.of(source)).replace("\r\n","\n"));
        bin=Files.createDirectory(directory.resolve("bin"));
        Files.writeString(bin.resolve("docker"),"#!/usr/bin/env bash\nif [[ \"$*\" == *mysqldump* ]]; then\n [[ \"${FIXTURE_DUMP_FAIL:-0}\" != 1 ]] || exit 27\n printf 'CREATE TABLE fixture (id INT);\\n'\nelse\n printf '01234567-1234-1234-1234-123456789012\\n'\nfi\n");
        Files.writeString(bin.resolve("flock"),"#!/usr/bin/env bash\n[[ \"${FIXTURE_LOCK_BUSY:-0}\" != 1 ]]\n");
        Files.writeString(bin.resolve("install"),"#!/usr/bin/env bash\n/usr/bin/mkdir -p -- \"${@: -1}\"\n");
        Files.writeString(bin.resolve("date"),"#!/usr/bin/env bash\nif [[ \"$*\" == *%Y%m%d* ]]; then printf '20260906-100000\\n'; else printf '2026-09-06T10:00:00Z\\n'; fi\n");
        key=directory.resolve("synthetic.key");Files.writeString(key,"synthetic-backup-fixture-only");
        config=directory.resolve("synthetic.env");Files.writeString(config,"SPRING_DB_USERNAME=fixture\nSPRING_DB_PASSWORD=synthetic-only\n");
    }
    private int run(Path backups, boolean failDump, boolean busy)throws Exception {
        var pb=new ProcessBuilder(bash,"-c","export PATH=\"$1:/usr/bin:/bin:/mingw64/bin:$PATH\"; chmod +x \"$1/docker\" \"$1/flock\" \"$1/date\" \"$1/install\"; exec bash \"$2\"","fixture",unix(bin),unix(script));
        pb.environment().putAll(Map.of("GEUPDDONG_BACKUP_DIR",unix(backups),
                "GEUPDDONG_BACKUP_KEY_FILE",key.toString().replace('\\','/'),
                "GEUPDDONG_API_ENV_FILE",config.toString().replace('\\','/'),
                "GEUPDDONG_DATABASE_EPOCH","01234567-1234-1234-1234-123456789014",
                "FIXTURE_DUMP_FAIL",failDump?"1":"0","FIXTURE_LOCK_BUSY",busy?"1":"0"));
        Path log=directory.resolve(UUID.randomUUID()+".log");pb.redirectErrorStream(true).redirectOutput(log.toFile());
        Process p=pb.start();if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();fail("Fixture process timeout");}
        String output=Files.readString(log);
        lastOutput=output;
        assertFalse(output.contains("synthetic-only"));assertFalse(output.contains("CREATE TABLE"));
        if(p.exitValue()==0)assertTrue(output.contains("MYSQL_BACKUP_COMPLETE"));
        else assertTrue(output.contains("MYSQL_BACKUP_FAILED"),output);
        return p.exitValue();
    }
    @Test void pipelineMetadataFailureAndNoOverwriteGuards()throws Exception {
        setup();Path success=directory.resolve("success");
        int status=run(success,false,false);assertEquals(0,status,lastOutput);
        var inventory=BackupEvidenceInventory.scan(success,Instant.now());
        assertEquals(1,inventory.files().size());var entry=inventory.files().getFirst();
        assertNotNull(entry.capture());assertEquals(entry.sha256(),entry.capture().sha256());
        byte[] original=Files.readAllBytes(success.resolve(entry.filename()));
        assertNotEquals(0,run(success,false,false)); // deterministic timestamp collides: never overwrite
        assertArrayEquals(original,Files.readAllBytes(success.resolve(entry.filename())));
        Path failure=directory.resolve("failure");assertNotEquals(0,run(failure,true,false));
        try(var files=Files.list(failure)){assertTrue(files.noneMatch(f->f.getFileName().toString().startsWith("toilet-db-")));}
        Path busy=directory.resolve("busy");assertNotEquals(0,run(busy,false,true));
        try(var files=Files.list(busy)){assertEquals(1,files.count());} // only the advisory lock fixture
    }
    @Test void anomalousLockIsPreservedInsteadOfTruncated()throws Exception {
        setup();Path backups=Files.createDirectory(directory.resolve("anomalous-lock"));
        Path lock=backups.resolve(".backup.lock");Files.writeString(lock,"SYNTHETIC_LOCK_ANOMALY");
        assertNotEquals(0,run(backups,false,false));assertEquals("SYNTHETIC_LOCK_ANOMALY",Files.readString(lock));
        try(var files=Files.list(backups)){assertEquals(1,files.count());}
    }
}
