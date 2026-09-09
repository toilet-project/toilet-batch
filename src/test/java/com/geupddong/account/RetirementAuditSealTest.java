package com.geupddong.account;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class RetirementAuditSealTest {
    @TempDir Path directory;
    final String id=RetirementRecoveryTest.ID;final Instant now=RetirementRecoveryTest.NOW;
    final ErasureCipher cipher=new ErasureCipher("test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
    final FileErasureObjectStoreTest.TestSafety safety=new FileErasureObjectStoreTest.TestSafety();
    RetirementOperationalEvidence.Audit audit() {
        var cutoffs=new TreeMap<String,String>();RetirementOperationalEvidence.SOURCES.forEach(s->cutoffs.put(s,now.toString()));
        return new RetirementOperationalEvidence.Audit(1,"test",id,id,now.toString(),"LOCAL_RETENTION_V1","a".repeat(64),
                now.toString(),now.minusSeconds(40*86400).toString(),cutoffs);
    }
    Path input() throws Exception {Path file=directory.resolve("reviewed.json");Files.write(file,new ObjectMapper().writeValueAsBytes(audit()));return file;}
    Path output(){return directory.resolve("retirement-audit.bin");}
    void seal(Path input,Clock clock){RetirementAuditSealCli.seal(input,output(),"test",id,"a".repeat(64),id,cipher,safety,clock,()->{});}
    @Test void reviewedAuditIsEncryptedAuthenticatedAndCanBeReplaced() throws Exception {
        var input=input();seal(input,Clock.fixed(now,ZoneOffset.UTC));
        assertEquals(audit(),RetirementOperationalEvidence.authenticatedFile(output(),"test",id,cipher,safety).read());
        assertFalse(new String(Files.readAllBytes(output()),java.nio.charset.StandardCharsets.UTF_8).contains("LOCAL_RETENTION_V1"));
        seal(input,Clock.fixed(now,ZoneOffset.UTC));assertFalse(Files.exists(directory.resolve("retirement-audit.pending")));
    }
    @Test void expiredOrIncompleteEvidenceCannotReplaceExistingAudit() throws Exception {
        var input=input();seal(input,Clock.fixed(now,ZoneOffset.UTC));var before=Files.readAllBytes(output());
        assertThrows(IllegalStateException.class,()->seal(input,Clock.fixed(now.plusSeconds(601),ZoneOffset.UTC)));
        Files.writeString(input,"{}");assertThrows(IllegalStateException.class,()->seal(input,Clock.fixed(now,ZoneOffset.UTC)));
        assertArrayEquals(before,Files.readAllBytes(output()));
    }
    @Test void orphanStageFileRequiresInspectionAndDoesNotGetOverwritten() throws Exception {
        var input=input();Files.writeString(directory.resolve("retirement-audit.pending"),"partial");
        assertThrows(IllegalStateException.class,()->seal(input,Clock.fixed(now,ZoneOffset.UTC)));
        assertEquals("partial",Files.readString(directory.resolve("retirement-audit.pending")));assertFalse(Files.exists(output()));
    }
}
