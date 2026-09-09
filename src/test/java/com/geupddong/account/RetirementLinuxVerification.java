package com.geupddong.account;

import com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;
import com.fasterxml.jackson.databind.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Linux files/fsync/lease and two JVMs; synthetic Git transport, not live GitHub or power-loss proof. */
public final class RetirementLinuxVerification {
    static final String ID=RetirementRecoveryTest.ID, JOB=RetirementRecoveryTest.JOB;
    static final Clock CLOCK=Clock.fixed(RetirementRecoveryTest.NOW,ZoneOffset.UTC);
    static final ObjectMapper JSON=new ObjectMapper();
    record Saved(Map<String,byte[]> blobs,Map<String,Map<String,String>> trees,
                 Map<String,GitHubRetirementRecoveryStoreTest.Commit> commits,String revision,int sequence){}
    static void privateFile(Path file,byte[] bytes) throws Exception {
        try(var channel=FileChannel.open(file,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
    static Path directory(Path parent,String name) throws Exception {
        return Files.createDirectory(parent.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    static final class DiskGit extends GitHubRetirementRecoveryStoreTest.Git {
        final Path path;
        DiskGit(GitHubRetirementRecoveryStoreTest owner,Path path) throws Exception {
            owner.super();this.path=path;
            if(Files.exists(path)) {
                var saved=JSON.readValue(Files.readAllBytes(path),Saved.class);
                blobs.clear();blobs.putAll(saved.blobs());trees.clear();trees.putAll(saved.trees());
                commits.clear();commits.putAll(saved.commits());revision=saved.revision();sequence=saved.sequence();
            }
        }
        void persist() {
            try {
                Path next=path.resolveSibling("git.next");
                privateFile(next,JSON.writeValueAsBytes(new Saved(blobs,trees,commits,revision,sequence)));
                Files.move(next,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                new FileErasureObjectStore.LinuxSafety().syncDirectory(path.getParent());
            }catch(Exception e){throw new IllegalStateException("SYNTHETIC_GIT_WRITE_FAILED");}
        }
        @Override public JsonNode request(String method,String target,Object body) {
            var result=super.request(method,target,body);if(!method.equals("GET"))persist();return result;
        }
    }
    public static void main(String[] args) throws Exception {
        assertEquals(2,args.length);assertTrue(Set.of("--crash","--resume").contains(args[0]));
        Path root=Path.of(args[1]);assertTrue(root.isAbsolute());assertEquals(root,root.toRealPath());
        assertTrue(root.getFileName().toString().matches("retirement-verification-[a-f0-9]{16}"));
        assertEquals("RETIREMENT_SYNTHETIC_ONLY\n",Files.readString(root.resolve("SYNTHETIC_ONLY")));
        var safety=new FileErasureObjectStore.LinuxSafety();safety.directory(root);
        boolean initialize=args[0].equals("--crash");
        if(initialize) {
            var objects=directory(root,"objects");var journal=directory(root,"journal");var maintenance=directory(root,"maintenance");
            privateFile(objects.resolve(".ledger-store"),FileErasureObjectStore.markerBytes("test",ID));
            privateFile(objects.resolve(".ledger.lock"),new byte[0]);
            privateFile(journal.resolve(".retirement-store"),RetirementRecoveryJournal.marker("test",ID));
            privateFile(journal.resolve(".retirement.lock"),new byte[0]);
            privateFile(maintenance.resolve(".maintenance.lock"),new byte[0]);
        }
        var cipher=new ErasureCipher("synthetic",Map.of("synthetic",Base64.getEncoder().encodeToString(new byte[32])));
        var objects=new FileErasureObjectStore(root.resolve("objects"),"test",ID);
        var journal=new RetirementRecoveryJournal(root.resolve("journal"),"test",ID,cipher);
        var git=new DiskGit(new GitHubRetirementRecoveryStoreTest(),root.resolve("git.json"));
        var held=new AtomicBoolean();
        RetirementRecoveryCoordinator.Lease lease=work->{
            try(var guard=LocalMaintenanceLease.acquire(root.resolve("maintenance/.maintenance.lock"),1000)) {
                assertTrue(held.compareAndSet(false,true));try{work.run();}finally{held.set(false);}
            }
        };
        var context=new RetirementExecutionContext(objects,cipher,"test",ID,(r,c)->
                new Evidence(Instant.parse(c.firstConfirmedAbsentAt()),CLOCK.instant(),EnumSet.allOf(Requirement.class)),
                CLOCK,()->assertTrue(held.get()));
        var history=new GitHubErasureHistoryStore(git,CLOCK);
        var service=new RetirementMaintenanceService(journal,history,context,lease,CLOCK);
        if(initialize) {
            var raw=new ObjectErasureLedger(objects,cipher,"test",true);
            for(int i=1;i<=2;i++) {
                var record=new ErasureRecord(1,"test",i,"2026-01-01T00:00",new UUID(0,i).toString(),"2026-04-01T00:00");
                raw.ensureRecorded(record);raw.ensureCompletion(ErasureCompletion.observed(record,ID,CLOCK.instant().minusSeconds(33*86400)));
            }
            var values=new TreeMap<String,String>();raw.intentsAtMost(2).forEach(r->values.put(r.objectKey(),ErasureCompletion.digest(r)));
            var original=history.read().checkpoint();
            git.replace(GitHubErasureHistoryStore.checkpointPath(1),new ErasureCheckpoint(1,"test",ID,1,2,
                    original.inventoryDigest(values),"",original.recordedAt()).bytes());git.persist();
            service.prepare(JOB,"v1/test/"+ID+".bin");
            lease.run(()->{
                var entry=journal.read(JOB);var adapter=new RetirementHistoryAdapter(history,CLOCK);
                context.verify(entry,adapter.read());adapter.advance(adapter.read(),entry,1);
                var target=entry.targets().getFirst();objects.removeExact(target.key(),target.ciphertextSha256(),true);
                System.out.println("SYNTHETIC_CRASH_AFTER_ARMED_DELETE");System.out.flush();
                Runtime.getRuntime().halt(73);
            });
        } else {
            assertEquals(1,history.inspect().latest().getLast().phase());
            assertNull(objects.read("v1/test/"+ID+".bin"));
            service.resume(JOB);service.cleanup(JOB);assertEquals(1,history.read().checkpoint().count());
            String second=new UUID(0,100).toString();service.prepare(second,"v1/test/"+new UUID(0,2)+".bin");
            service.resume(second);service.cleanup(second);assertEquals(0,history.read().checkpoint().count());
            assertTrue(journal.operations(0).isEmpty());lease.run(()->assertTrue(context.inventory().isEmpty()));
            assertThrows(IllegalStateException.class,()->new GitHubErasureCheckpointStore(git).read());
            System.out.println("PASS real-linux-fsync process-restart lease-release two-retirements journal-cleanup legacy-block");
        }
    }
}
