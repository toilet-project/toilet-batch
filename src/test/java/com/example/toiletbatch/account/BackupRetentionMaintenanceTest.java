package com.example.toiletbatch.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupRetentionMaintenanceTest {
    @TempDir Path temporary;
    final Instant now=Instant.parse("2026-09-06T12:00:00Z");
    final String id="01234567-1234-1234-1234-123456789012";
    final BackupRetentionMaintenance maintenance=new BackupRetentionMaintenance();
    Path root; BackupRetentionMaintenance.Context context;
    String old="toilet-db-20260823-120000.sql.gz.enc", fresh="toilet-db-20260906-110000.sql.gz.enc";
    String backup(String name,Instant end)throws Exception{
        byte[] bytes=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.write(root.resolve(name),bytes);Files.setLastModifiedTime(root.resolve(name),FileTime.from(end));
        Files.writeString(root.resolve(name+".sha256"),hash+"  "+name);
        var metadata=new BackupCaptureMetadata(1,name,hash,bytes.length,end.minusSeconds(60).toString(),end.toString(),"toilet_db",id,id);
        Files.write(root.resolve(name+".metadata.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(metadata));
        return hash;
    }
    void fixture()throws Exception{
        root=Files.createDirectory(temporary.resolve("backups"));
        backup(old,now.minus(Duration.ofDays(14)));
        context=new BackupRetentionMaintenance.Context(id,id,backup(fresh,now.minusSeconds(3600)));
        Files.createFile(root.resolve(".backup.lock"));
    }
    BackupRetentionMaintenance.Plan plan(){return maintenance.plan(BackupEvidenceInventory.scan(root,now),now,context);}
    @Test void exactlyFourteenDaysExpiresButRecentVerifiedBackupIsKept()throws Exception{
        fixture();assertEquals(BackupRetentionMaintenance.Status.READY,plan().status());assertEquals(1,plan().expired().size());
        var inventory=BackupEvidenceInventory.scan(root,now.minusSeconds(1));
        assertEquals(BackupRetentionMaintenance.Status.NO_EXPIRED,maintenance.plan(inventory,now.minusSeconds(1),context).status());
    }
    @Test void legacyAndUnknownFilesBlockDeletion()throws Exception{
        fixture();Files.delete(root.resolve(old+".metadata.json"));
        assertEquals(BackupRetentionMaintenance.Status.HOLD_LEGACY_METADATA,plan().status());
        Files.writeString(root.resolve("unclassified.txt"),"fixture");
        assertEquals(BackupRetentionMaintenance.Status.HOLD_UNKNOWN_FILES,plan().status());
    }
    @Test void identityMissingVerificationAndStaleScanBlock()throws Exception{
        fixture();var inventory=BackupEvidenceInventory.scan(root,now);
        var other=new BackupRetentionMaintenance.Context("01234567-1234-1234-1234-123456789013",id,context.restoreVerifiedBackupSha256());
        assertEquals(BackupRetentionMaintenance.Status.HOLD_IDENTITY,maintenance.plan(inventory,now,other).status());
        other=new BackupRetentionMaintenance.Context(id,id,"0".repeat(64));
        assertEquals(BackupRetentionMaintenance.Status.HOLD_NO_RECENT_VERIFIED_BACKUP,maintenance.plan(inventory,now,other).status());
        assertEquals(BackupRetentionMaintenance.Status.HOLD_STALE_SCAN,maintenance.plan(inventory,now.plusSeconds(301),context).status());
    }
    @Test void oldVerifiedBackupDoesNotPermitCleanup()throws Exception{
        fixture();context=new BackupRetentionMaintenance.Context(id,id,backup(fresh,now.minus(Duration.ofHours(37))));
        assertEquals(BackupRetentionMaintenance.Status.HOLD_NO_RECENT_VERIFIED_BACKUP,plan().status());
    }
    @Test void approvedPlanDeletesOnlyExpiredFixtureTripletAndJournals()throws Exception{
        fixture();var journal=temporary.resolve("cleanup.journal");
        assertEquals(1,maintenance.apply(root,context,plan().digest(),journal,Clock.fixed(now,ZoneOffset.UTC),true,Files::delete));
        assertFalse(Files.exists(root.resolve(old)));assertTrue(Files.exists(root.resolve(fresh)));
        assertTrue(Files.readString(journal).endsWith("COMPLETE\n"));
        assertEquals(BackupRetentionMaintenance.Status.NO_EXPIRED,plan().status());
    }
    @Test void noApprovalNoLockAndExistingJournalCannotDelete()throws Exception{
        fixture();var journal=temporary.resolve("cleanup.journal");var clock=Clock.fixed(now,ZoneOffset.UTC);
        assertThrows(IllegalStateException.class,()->maintenance.apply(root,context,"0".repeat(64),journal,clock,true,Files::delete));
        assertThrows(IllegalStateException.class,()->maintenance.apply(root,context,plan().digest(),journal,clock,false,Files::delete));
        Files.writeString(journal,"previous");
        assertThrows(FileAlreadyExistsException.class,()->maintenance.apply(root,context,plan().digest(),journal,clock,true,Files::delete));
        assertTrue(Files.exists(root.resolve(old)));assertEquals("previous",Files.readString(journal));
    }
    @Test void changedInventoryRejectsOldApproval()throws Exception{
        fixture();String approved=plan().digest();backup("toilet-db-20260905-110000.sql.gz.enc",now.minusSeconds(86400));
        assertThrows(IllegalStateException.class,()->maintenance.apply(root,context,approved,temporary.resolve("journal"),Clock.fixed(now,ZoneOffset.UTC),true,Files::delete));
        assertTrue(Files.exists(root.resolve(old)));
    }
    @Test void partialFailureIsRecordedAndOrphansBlockRetry()throws Exception{
        fixture();Path journal=temporary.resolve("partial.journal");
        assertThrows(java.io.IOException.class,()->maintenance.apply(root,context,plan().digest(),journal,Clock.fixed(now,ZoneOffset.UTC),true,p->{
            if(p.toString().endsWith(".sha256"))throw new java.io.IOException("fixture failure");Files.delete(p);
        }));
        assertTrue(Files.readString(journal).contains("INCOMPLETE_REVIEW_REQUIRED"));
        assertEquals(BackupRetentionMaintenance.Status.HOLD_UNKNOWN_FILES,plan().status());
        assertTrue(Files.exists(root.resolve(fresh)));
    }
}
