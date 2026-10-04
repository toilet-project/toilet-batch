package com.geupddong.review;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.geupddong.account.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Local encrypted write-ahead intent plus independent aggregate checkpoint. No auto-bootstrap/delete.
 * An acknowledged intent may outlive a rolled-back SQL request; replay conservatively completes the
 * owner's already authorized unlink. Missing/partial/unacknowledged inventories never become an empty set.
 */
public final class ReviewUnlinkJournal implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ErasureObjectStore objects;
    private final ErasureCipher cipher;
    private final CheckpointedErasureLedger.Store checkpoints;
    private final CheckpointedErasureLedger.Exclusive exclusive;
    private final String epoch;
    private final Clock clock;
    /** Only this journal can produce a verified snapshot; callers cannot construct a partial replay set. */
    public static final class Snapshot {
        private final CheckpointedErasureLedger.Head head;
        private final List<ReviewUnlinkRecord> records;
        private Snapshot(CheckpointedErasureLedger.Head head,List<ReviewUnlinkRecord> records) {
            this.head=head;this.records=List.copyOf(records);
        }
        public CheckpointedErasureLedger.Head head(){return head;}
        public List<ReviewUnlinkRecord> records(){return records;}
    }
    public ReviewUnlinkJournal(ErasureObjectStore objects,ErasureCipher cipher,CheckpointedErasureLedger.Store checkpoints,
            CheckpointedErasureLedger.Exclusive exclusive,String epoch,Clock clock) {
        if(!UUID.fromString(epoch).toString().equals(epoch))throw failure();
        this.objects=objects;this.cipher=cipher;this.checkpoints=checkpoints;this.exclusive=exclusive;this.epoch=epoch;this.clock=clock;
    }
    private static byte[] bytes(ReviewUnlinkRecord record) {
        try{return JSON.writeValueAsBytes(record);}catch(Exception ignored){throw failure();}
    }
    private static String catalogue(String key){return key.replaceFirst("^v1/","catalogue-v1/");}
    private ReviewUnlinkRecord read(String key) {
        try{
            var record=JSON.readValue(cipher.decryptDocument(ReviewUnlinkRecord.REALM,key,Objects.requireNonNull(objects.read(key))),ReviewUnlinkRecord.class);
            if(!key.equals(record.key()) && !key.equals(catalogue(record.key())))throw failure();
            return record;
        }catch(Exception ignored){throw failure();}
    }
    private void put(String key,ReviewUnlinkRecord record){
        objects.putIfAbsent(key,cipher.encryptDocument(record.realm(),key,bytes(record)));
        if(!record.equals(read(key)))throw failure();
    }
    private TreeMap<String,ReviewUnlinkRecord> inventory(String prefix,int maximum){
        var records=new TreeMap<String,ReviewUnlinkRecord>();
        for(String key:objects.list(prefix+"/"+ReviewUnlinkRecord.REALM+"/",maximum)){
            var record=read(key);if(records.put(record.key(),record)!=null)throw failure();
        }
        return records;
    }
    private static Map<String,String> digests(Map<String,ReviewUnlinkRecord> records){
        var values=new TreeMap<String,String>();records.forEach((key,value)->values.put(key,ErasureCheckpoint.hash(bytes(value))));return values;
    }
    private void validate(ErasureCheckpoint cp){
        if(!ReviewUnlinkRecord.REALM.equals(cp.realm()) || !epoch.equals(cp.databaseEpoch()) || cp.count()>100000
                || Instant.parse(cp.recordedAt()).isAfter(clock.instant()))throw failure();
    }
    private boolean matches(ErasureCheckpoint cp,Map<String,ReviewUnlinkRecord> records){
        return cp.count()==records.size() && cp.inventorySha256().equals(cp.inventoryDigest(digests(records)));
    }
    private TreeMap<String,ReviewUnlinkRecord> reconcile(ErasureCheckpoint cp,TreeMap<String,ReviewUnlinkRecord> actual,ReviewUnlinkRecord request){
        var without=new TreeMap<>(actual);without.remove(request.key());
        var with=new TreeMap<>(actual);with.put(request.key(),request);
        if(!matches(cp,actual) && !matches(cp,without) && !matches(cp,with))throw failure();
        return with;
    }
    public void ensureRecorded(String reviewKey){
        var request=new ReviewUnlinkRecord(1,ReviewUnlinkRecord.REALM,reviewKey);
        try{exclusive.run(()->{
            var head=checkpoints.read();var cp=head.checkpoint();validate(cp);
            var intents=reconcile(cp,inventory("v1",cp.count()+1),request);
            var catalogue=reconcile(cp,inventory("catalogue-v1",cp.count()+1),request);
            if(!intents.equals(catalogue) || intents.size()>100000)throw failure();
            put(catalogue(request.key()),request);put(request.key(),request);
            if(!intents.equals(inventory("v1",cp.count()+1)) || !intents.equals(inventory("catalogue-v1",cp.count()+1)))throw failure();
            if(!matches(cp,intents))checkpoints.append(head,new ErasureCheckpoint(1,ReviewUnlinkRecord.REALM,epoch,
                    Math.addExact(cp.sequence(),1),intents.size(),cp.inventoryDigest(digests(intents)),cp.digest(),clock.instant().toString()));
        });}catch(RuntimeException ignored){throw failure();}
    }
    public Snapshot snapshot(){
        try{
            var head=checkpoints.read();var cp=head.checkpoint();validate(cp);
            var intents=inventory("v1",cp.count()+1);var catalogue=inventory("catalogue-v1",cp.count()+1);
            if(!intents.equals(catalogue) || !matches(cp,intents) || !head.equals(checkpoints.read()))throw failure();
            return new Snapshot(head,new ArrayList<>(intents.values()));
        }catch(RuntimeException ignored){throw failure();}
    }
    @Override public void close(){objects.close();}
    private static IllegalStateException failure(){return new IllegalStateException("REVIEW_UNLINK_JOURNAL_UNAVAILABLE");}
}
