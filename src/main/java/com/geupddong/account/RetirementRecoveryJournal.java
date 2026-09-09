package com.geupddong.account;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Separate, pre-provisioned private directory. One bounded member per immutable encrypted job.
 * A journal authenticates recovery data, NOT permission to delete.
 */
final class RetirementRecoveryJournal {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Target(String key, String ciphertextSha256) {
        Target { if (key == null || !hash(ciphertextSha256)) throw failure(); }
        @Override public String toString() { return "RetirementTarget[redacted]"; }
    }
    record Recovery(ErasureRecord record,ErasureCompletion completion,ErasureCheckpoint checkpoint,
                    String evidenceCheckedAt,List<String> verified) {
        Recovery {
            if(record==null || completion==null || checkpoint==null || evidenceCheckedAt==null)throw failure();
            verified=List.copyOf(verified);Instant.parse(evidenceCheckedAt);
        }
        @Override public String toString(){return "RetirementRecovery[redacted]";}
    }
    record Entry(int version, String realm, String epoch, String operationId,
                 String preparedAt, String validUntil, String checkpointDigest,
                 String checkpointRevision, String planDigest, int beforeCount,
                 String afterInventoryDigest, String retainedObjectsDigest, List<Target> targets,Recovery recovery) {
        // Legacy synthetic fixtures only. Runtime execution rejects a missing semantic recovery payload.
        Entry(int version,String realm,String epoch,String operationId,String preparedAt,String validUntil,String checkpointDigest,
              String checkpointRevision,String planDigest,int beforeCount,String afterInventoryDigest,String retainedObjectsDigest,List<Target> targets) {
            this(version,realm,epoch,operationId,preparedAt,validUntil,checkpointDigest,checkpointRevision,planDigest,beforeCount,
                    afterInventoryDigest,retainedObjectsDigest,targets,null);
        }
        Entry {
            try {
                if (version != 1 || realm == null || !realm.matches("[a-z0-9-]{3,40}")
                        || !uuid(epoch) || !uuid(operationId) || !hash(checkpointDigest) || !hash(planDigest)
                        || !hash(afterInventoryDigest) || !hash(retainedObjectsDigest)
                        || checkpointRevision == null || !checkpointRevision.matches("[a-zA-Z0-9_-]{1,128}")
                        || beforeCount < 1 || beforeCount > 100000) throw failure();
                var start = Instant.parse(preparedAt); var end = Instant.parse(validUntil);
                if (!start.toString().equals(preparedAt) || !end.toString().equals(validUntil)
                        || end.isBefore(start) || end.isAfter(start.plusSeconds(600))) throw failure();
                targets = List.copyOf(targets);
                if (targets.size() != 3) throw failure();
                String prefix = "v1/" + realm + "/";
                String first = targets.getFirst().key();
                if (!first.startsWith(prefix) || !first.endsWith(".bin")) throw failure();
                String id = first.substring(prefix.length(), first.length()-4);
                if (!uuid(id) || !targets.get(1).key().equals("catalogue-v1/"+realm+"/"+id+".bin")
                        || !targets.get(2).key().equals("completion-v1/"+realm+"/"+epoch+"/"+id+".bin")) throw failure();
            } catch (RuntimeException invalid) { throw failure(); }
        }
        byte[] bytes() {
            try { byte[] bytes = JSON.writeValueAsBytes(this); if (bytes.length > 8000) throw failure(); return bytes; }
            catch (Exception ignored) { throw failure(); }
        }
        String digest() { return ErasureCheckpoint.hash(bytes()); }
        String objectKey() { return "retirement-v1/"+realm+"/"+operationId+".bin"; }
        @Override public String toString() { return "RetirementRecoveryEntry[targets=3, authorization=false]"; }
    }

    private final Path root;
    private final String realm;
    private final byte[] marker;
    private final ErasureCipher cipher;
    private final FileErasureObjectStore.Safety safety;
    RetirementRecoveryJournal(Path root, String realm, String storeId, ErasureCipher cipher) {
        this(root, realm, storeId, cipher, new FileErasureObjectStore.LinuxSafety());
    }
    // Test-only filesystem checks, no environment switch to bypass Linux requirements.
    RetirementRecoveryJournal(Path root, String realm, String storeId, ErasureCipher cipher,
                              FileErasureObjectStore.Safety safety) {
        if (root == null || !root.isAbsolute() || !root.equals(root.normalize()) || root.getParent() == null
                || realm == null || !realm.matches("[a-z0-9-]{3,40}") || !uuid(storeId)) throw failure();
        this.root=root; this.realm=realm; this.cipher=Objects.requireNonNull(cipher); this.safety=safety;
        this.marker=marker(realm,storeId);
        locked(() -> { safety.syncDirectory(root); return null; });
    }
    static byte[] marker(String realm,String storeId) {
        return ("LOCAL_RETIREMENT_RECOVERY_V1\n"+realm+"\n"+storeId+"\n").getBytes(StandardCharsets.US_ASCII);
    }
    private Path path(String operation) {
        if (!uuid(operation)) throw failure();
        return root.resolve("retirement-"+operation+".bin");
    }
    private interface IO<T> { T run() throws Exception; }
    private <T> T locked(IO<T> work) {
        try {
            for (Path p=root;p!=null;p=p.getParent()) if (Files.isSymbolicLink(p)) throw failure();
            if (!root.toRealPath().equals(root)) throw failure();
            safety.directory(root);
            if (!Arrays.equals(marker,readBytes(root.resolve(".retirement-store")))) throw failure();
            Path lockPath=root.resolve(".retirement.lock"); safety.file(root,lockPath);
            try (var channel=FileChannel.open(lockPath,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                 var lock=channel.tryLock()) {
                if (lock==null) throw failure();
                return work.run();
            }
        } catch (Exception ignored) { throw failure(); }
    }
    private byte[] readBytes(Path path) throws Exception {
        safety.file(root,path);
        try(var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            long size=channel.size(); if(size<1 || size>8192) throw failure();
            var buffer=ByteBuffer.allocate((int)size);
            while(buffer.hasRemaining()) if(channel.read(buffer)<0) throw failure();
            if(channel.size()!=size) throw failure();
            return buffer.array();
        }
    }
    private Entry decode(String operation,byte[] bytes) throws Exception {
        String key="retirement-v1/"+realm+"/"+operation+".bin";
        byte[] plain=cipher.decryptDocument(realm,key,bytes);
        Entry entry=JSON.readValue(plain,Entry.class);
        if(!entry.realm().equals(realm) || !entry.operationId().equals(operation)
                || !Arrays.equals(plain,entry.bytes())) throw failure();
        return entry;
    }
    Entry read(String operation) {
        Path file=path(operation);
        // Missing journal is an error, never an empty genesis or a new job.
        return locked(() -> decode(operation,readBytes(file)));
    }
    List<String> operations(int maximum) {
        if(maximum<0 || maximum>100000) throw failure();
        return locked(() -> {
            var result=new ArrayList<String>();
            try(var files=Files.newDirectoryStream(root)) {
                int scanned=0;
                for(Path file:files) {
                    if(++scanned>100002) throw failure();
                    String name=file.getFileName().toString();
                    if(name.equals(".retirement-store") || name.equals(".retirement.lock")) continue;
                    if(!name.startsWith("retirement-") || !name.endsWith(".bin")) throw failure();
                    String operation=name.substring(11,name.length()-4);
                    if(!path(operation).equals(file) || result.size()>=maximum) throw failure();
                    // Corrupt/incomplete recovery records must not disappear from an apparently healthy listing.
                    decode(operation,readBytes(file));
                    result.add(operation);
                }
            }
            result.sort(String::compareTo); return List.copyOf(result);
        });
    }
    void put(Entry entry) {
        if(entry==null || !entry.realm().equals(realm)) throw failure();
        Path file=path(entry.operationId());
        locked(() -> {
            boolean created=false;
            try { safety.createFile(file); created=true; }
            catch(FileAlreadyExistsException exists) { /* immutable exact retry */ }
            if(!created && !decode(entry.operationId(),readBytes(file)).equals(entry)) throw failure();
            safety.file(root,file);
            try(var channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                if(created) {
                    var bytes=ByteBuffer.wrap(cipher.encryptDocument(realm,entry.objectKey(),entry.bytes()));
                    while(bytes.hasRemaining()) channel.write(bytes);
                }
                channel.force(true);
            }
            safety.syncDirectory(root);
            if(!decode(entry.operationId(),readBytes(file)).equals(entry)) throw failure();
            return null;
        });
    }
    void removeCompleted(Entry expected) {
        if(expected==null || !realm.equals(expected.realm()))throw failure();
        Path file=path(expected.operationId());
        locked(()->{
            if(!decode(expected.operationId(),readBytes(file)).equals(expected))throw failure();
            safety.file(root,file);Files.delete(file);safety.syncDirectory(root);return null;
        });
    }
    void confirmEmpty() {
        if(!operations(1).isEmpty())throw failure();
        locked(()->{safety.syncDirectory(root);return null;});
    }
    static boolean hash(String value) { return value!=null && value.matches("[a-f0-9]{64}"); }
    private static boolean uuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); } catch(Exception e) {return false;}
    }
    private static IllegalStateException failure() {return new IllegalStateException("RETIREMENT_RECOVERY_UNAVAILABLE");}
}
