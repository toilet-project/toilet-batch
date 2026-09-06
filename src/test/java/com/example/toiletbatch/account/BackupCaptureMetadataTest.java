package com.example.toiletbatch.account;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BackupCaptureMetadataTest {
    @TempDir Path directory;
    private final Instant now=Instant.parse("2026-09-06T12:00:00Z");
    private final String id="01234567-1234-1234-1234-123456789012";
    private BackupCaptureMetadata fixture()throws Exception {
        String name="toilet-db-20260906-100000.sql.gz.enc";
        byte[] bytes={1,2,3};Path file=directory.resolve(name);Files.write(file,bytes);
        Files.setLastModifiedTime(file,FileTime.from(now));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(directory.resolve(name+".sha256"),hash+"  "+name);
        return new BackupCaptureMetadata(1,name,hash,bytes.length,"2026-09-06T10:00:00Z","2026-09-06T11:00:00Z","toilet_db",id,id);
    }
    private void write(BackupCaptureMetadata m)throws Exception{
        Files.write(directory.resolve(m.filename()+".metadata.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(m));
    }
    @Test void captureStartNotCompletionOrMtimeMarksPotentialPreErasureCopy()throws Exception{
        var m=fixture();write(m);var inventory=BackupEvidenceInventory.scan(directory,now);
        var result=inventory.compare(Instant.parse("2026-09-06T10:30:00Z"));
        assertEquals(0,inventory.unclassifiedEntries());assertEquals(0,result.captureMetadataUnknown());
        assertEquals(0,result.modifiedBeforeConfirmation());assertEquals(1,result.captureStartedBeforeConfirmation());
        assertFalse(result.allCopiesCleared());
    }
    @Test void legacyDumpIsNotBackfilledFromFilename()throws Exception{
        fixture();assertEquals(1,BackupEvidenceInventory.scan(directory,now).compare(now).captureMetadataUnknown());
    }
    @Test void metadataMustMatchActualEncryptedBytes()throws Exception{
        var m=fixture();write(new BackupCaptureMetadata(1,m.filename(),"0".repeat(64),m.bytes(),m.captureStartedAt(),m.captureCompletedAt(),m.database(),id,id));
        assertThrows(IllegalStateException.class,()->BackupEvidenceInventory.scan(directory,now));
    }
    @Test void reversedTimesAndInvalidServerIdentityAreRejected()throws Exception{
        var m=fixture();
        assertThrows(IllegalArgumentException.class,()->new BackupCaptureMetadata(1,m.filename(),m.sha256(),m.bytes(),m.captureCompletedAt(),m.captureStartedAt(),m.database(),id,id));
        assertThrows(IllegalArgumentException.class,()->new BackupCaptureMetadata(1,m.filename(),m.sha256(),m.bytes(),m.captureStartedAt(),m.captureCompletedAt(),m.database(),"wrong",id));
    }
    @Test void orphanMetadataRemainsUnclassified()throws Exception{
        Files.writeString(directory.resolve("toilet-db-20260906-100000.sql.gz.enc.metadata.json"),"{}");
        assertEquals(1,BackupEvidenceInventory.scan(directory,now).unclassifiedEntries());
    }
}
