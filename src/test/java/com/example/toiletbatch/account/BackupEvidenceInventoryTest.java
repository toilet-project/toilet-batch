package com.example.toiletbatch.account;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BackupEvidenceInventoryTest {
    @TempDir Path directory;
    private static final Instant NOW=Instant.parse("2026-09-06T12:00:00Z");
    private Path fixture(boolean absoluteChecksum) throws Exception {
        Path file=directory.resolve("toilet-db-20260901-000000.sql.gz.enc");
        byte[] bytes={1,2,3,4,5};Files.write(file,bytes);
        Files.setLastModifiedTime(file,FileTime.from(NOW.minusSeconds(100)));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.writeString(directory.resolve(file.getFileName()+".sha256"),hash+"  "+(absoluteChecksum?file:file.getFileName())+"\n");
        return file;
    }
    @Test void scanValidatesCiphertextWithoutChangingItOrInferringSnapshotTime() throws Exception {
        Path file=fixture(true);byte[] before=Files.readAllBytes(file);
        var inventory=BackupEvidenceInventory.scan(directory,NOW);
        assertEquals(1,inventory.files().size());assertEquals(0,inventory.unclassifiedEntries());
        var comparison=inventory.compare(NOW);
        assertEquals(1,comparison.modifiedBeforeConfirmation());assertEquals(1,comparison.captureMetadataUnknown());
        assertFalse(comparison.allCopiesCleared());assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void relativeChecksumWorksButMissingOrForgedChecksumFails() throws Exception {
        Path file=fixture(false);assertEquals(1,BackupEvidenceInventory.scan(directory,NOW).files().size());
        Files.writeString(directory.resolve(file.getFileName()+".sha256"),"0".repeat(64)+"  "+file.getFileName());
        assertThrows(IllegalStateException.class,()->BackupEvidenceInventory.scan(directory,NOW));
        Files.delete(directory.resolve(file.getFileName()+".sha256"));
        assertThrows(IllegalStateException.class,()->BackupEvidenceInventory.scan(directory,NOW));
    }
    @Test void directoriesPartialFilesAndOrphanSidecarsStayUnclassified() throws Exception {
        fixture(false);Files.createDirectory(directory.resolve("copies"));Files.writeString(directory.resolve("partial.tmp"),"synthetic");
        Files.writeString(directory.resolve("toilet-db-20260101-000000.sql.gz.enc.sha256"),"synthetic");
        var inventory=BackupEvidenceInventory.scan(directory,NOW);
        assertEquals(3,inventory.unclassifiedEntries());assertFalse(inventory.compare(NOW).allCopiesCleared());
    }
    @Test void futureFileAndDifferentChecksumPathAreRejected() throws Exception {
        Path file=fixture(false);Files.setLastModifiedTime(file,FileTime.from(NOW.plusSeconds(1)));
        assertThrows(IllegalStateException.class,()->BackupEvidenceInventory.scan(directory,NOW));
        fixture(false);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        Files.writeString(directory.resolve(file.getFileName()+".sha256"),hash+"  /some/other/file");
        assertThrows(IllegalStateException.class,()->BackupEvidenceInventory.scan(directory,NOW));
    }
    @Test void emptyDirectoryDoesNotProveAbsenceOfBinlogsOrExternalCopies() {
        var inventory=BackupEvidenceInventory.scan(directory,NOW);
        assertEquals(0,inventory.files().size());assertFalse(inventory.compare(NOW).allCopiesCleared());
    }
}
