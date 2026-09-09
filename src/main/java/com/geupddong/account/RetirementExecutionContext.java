package com.geupddong.account;

import com.example.toiletbatch.account.ErasureRetirementPlanner;
import com.example.toiletbatch.account.ErasureRetentionReviewPolicy;
import com.example.toiletbatch.account.ErasureRetentionReviewPolicy.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import com.geupddong.account.RetirementRecoveryJournal.*;

/** Batch-only semantic bridge. Requires an externally held maintenance lease and live evidence.
 * Reconstruction is in memory for verification only; never restores deleted objects to disk.
 */
final class RetirementExecutionContext implements RetirementRecoveryCoordinator.Context,RetirementRecoveryCoordinator.Objects {
    interface EvidenceSource {Evidence current(ErasureRecord record,ErasureCompletion completion);}
    private final FileErasureObjectStore objects;
    private final ObjectErasureLedger ledger;
    private final ErasureCipher cipher;
    private final String realm,epoch;
    private final EvidenceSource evidence;
    private final Clock clock;
    private final Runnable requireLease;
    private final ObjectMapper json=new ObjectMapper();
    RetirementExecutionContext(FileErasureObjectStore objects,ErasureCipher cipher,String realm,String epoch,
                               EvidenceSource evidence,Clock clock,Runnable requireLease) {
        this.objects=objects;this.cipher=cipher;this.realm=realm;this.epoch=epoch;this.evidence=evidence;this.clock=clock;
        this.requireLease=requireLease;this.ledger=new ObjectErasureLedger(objects,cipher,realm,true);
    }
    Entry prepare(String operation,CheckpointedErasureLedger.Head head,String intentKey) {
        try {
            requireLease.run();Instant now=clock.instant();
            var intents=ledger.intentsAtMost(100000);var catalogue=ledger.catalogueAtMost(100000);
            ErasureRecord selected=intents.stream().filter(r->r.objectKey().equals(intentKey)).findFirst().orElseThrow();
            ErasureCompletion completion=completion(selected);
            Evidence current=evidence.current(selected,completion);
            var plan=plan(head,now,intents,catalogue,selected,completion,current);
            var raw=new TreeMap<>(inventory());var targets=new ArrayList<Target>();
            for(String key:keys(selected,completion))targets.add(new Target(key,raw.remove(key)));
            var remaining=identities(intents);remaining.remove(intentKey);
            var verified=current.verified().stream().map(Enum::name).sorted().toList();
            var recovery=new Recovery(selected,completion,head.checkpoint(),current.checkedAt().toString(),verified);
            return new Entry(1,realm,epoch,operation,now.toString(),plan.validUntil().toString(),head.checkpoint().digest(),head.revision(),
                    plan.planDigest(),intents.size(),head.checkpoint().inventoryDigest(remaining),
                    RetirementRecoveryCoordinator.inventoryDigest(raw),targets,recovery);
        }catch(Exception ignored){throw failure();}
    }
    @Override public void verify(Entry entry,RetirementRecoveryCoordinator.State state) {
        try {
            requireLease.run();Recovery recovery=Objects.requireNonNull(entry.recovery());
            var selected=recovery.record();var receipt=recovery.completion();var cp=recovery.checkpoint();
            if(!entry.realm().equals(realm) || !entry.epoch().equals(epoch) || !cp.digest().equals(entry.checkpointDigest())
                    || cp.count()!=entry.beforeCount() || !selected.realm().equals(realm) || !receipt.realm().equals(realm)
                    || !receipt.databaseEpoch().equals(epoch) || !receipt.withdrawalKey().equals(selected.withdrawalKey())
                    || !receipt.intentDigest().equals(ErasureCompletion.digest(selected))
                    || !entry.targets().stream().map(Target::key).toList().equals(keys(selected,receipt)))throw failure();
            var intents=restoreSelected(ledger.intentsAtMost(100000),selected);
            var catalogue=restoreSelected(ledger.catalogueAtMost(100000),selected);
            var verified=EnumSet.noneOf(Requirement.class);
            for(String name:recovery.verified())if(!verified.add(Requirement.valueOf(name)))throw failure();
            var originalEvidence=new Evidence(Instant.parse(receipt.firstConfirmedAbsentAt()),Instant.parse(recovery.evidenceCheckedAt()),verified);
            var head=new CheckpointedErasureLedger.Head(entry.checkpointRevision(),cp);
            var plan=plan(head,Instant.parse(entry.preparedAt()),intents,catalogue,selected,receipt,originalEvidence);
            var remaining=identities(intents);remaining.remove(selected.objectKey());
            if(!plan.planDigest().equals(entry.planDigest()) || !plan.validUntil().toString().equals(entry.validUntil())
                    || !cp.inventoryDigest(remaining).equals(entry.afterInventoryDigest()))throw failure();
            Evidence fresh=evidence.current(selected,receipt);
            if(fresh==null || !fresh.firstConfirmedAbsentAt().equals(originalEvidence.firstConfirmedAbsentAt())
                    || new ErasureRetentionReviewPolicy().evaluate(clock.instant(),fresh).status()!=Status.REVIEW_CANDIDATE)throw failure();
            // Authenticate even historical completion files, not just their names/ciphertext hashes.
            var known=new HashMap<String,ErasureRecord>();for(var record:intents)known.put(record.withdrawalKey(),record);
            for(String key:objects.list("completion-v1/"+realm+"/",100000)) {
                var actual=json.readValue(cipher.decryptDocument(realm,key,objects.read(key)),ErasureCompletion.class);
                var owner=known.get(actual.withdrawalKey());
                if(owner==null || !actual.objectKey().equals(key) || !actual.intentDigest().equals(ErasureCompletion.digest(owner)))throw failure();
                if(key.equals(receipt.objectKey()) && !actual.equals(receipt))throw failure();
            }
        }catch(Exception ignored){throw failure();}
    }
    @Override public String reviewDigest(Entry entry,RetirementRecoveryCoordinator.State state) {
        verify(entry,state);var r=entry.recovery();var current=evidence.current(r.record(),r.completion());
        if(current==null || !current.firstConfirmedAbsentAt().equals(Instant.parse(r.completion().firstConfirmedAbsentAt()))
                || new ErasureRetentionReviewPolicy().evaluate(clock.instant(),current).status()!=Status.REVIEW_CANDIDATE)throw failure();
        var values=List.of("retirement-reviewed-v1",entry.digest(),current.firstConfirmedAbsentAt().toString(),current.checkedAt().toString(),
                current.verified().stream().map(Enum::name).sorted().toList().toString());
        return ErasureCheckpoint.hash(String.join("\n",values).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private ErasureRetirementPlanner.Plan plan(CheckpointedErasureLedger.Head head,Instant at,List<ErasureRecord> intents,
                                               List<ErasureRecord> catalogue,ErasureRecord selected,ErasureCompletion receipt,Evidence proof) {
        var plan=new ErasureRetirementPlanner().plan(realm,epoch,at,at,head,intents,catalogue,
                Map.of(selected.objectKey(),new ErasureRetirementPlanner.Proof(receipt,proof)));
        if(plan.items().stream().noneMatch(i->i.targetDigest().equals(ErasureCompletion.digest(selected))
                && i.outcome()==ErasureRetirementPlanner.Outcome.REVIEW_CANDIDATE))throw failure();return plan;
    }
    private ErasureCompletion completion(ErasureRecord selected) throws Exception {
        String key="completion-v1/"+realm+"/"+epoch+"/"+selected.withdrawalKey()+".bin";
        byte[] bytes=objects.read(key);if(bytes==null)throw failure();
        var result=json.readValue(cipher.decryptDocument(realm,key,bytes),ErasureCompletion.class);
        if(!result.objectKey().equals(key) || !result.intentDigest().equals(ErasureCompletion.digest(selected)))throw failure();return result;
    }
    boolean reviewAgeReached(ErasureRecord selected) {
        try {
            requireLease.run();String key="completion-v1/"+realm+"/"+epoch+"/"+selected.withdrawalKey()+".bin";
            if(objects.read(key)==null)return false;
            return !clock.instant().isBefore(Instant.parse(completion(selected).firstConfirmedAbsentAt())
                    .plus(com.example.toiletbatch.account.ErasureRetentionReviewPolicy.MINIMUM_REVIEW_DELAY));
        }catch(Exception ignored){throw failure();}
    }
    private List<ErasureRecord> restoreSelected(List<ErasureRecord> current,ErasureRecord selected) {
        var result=new ArrayList<>(current);boolean found=false;
        for(var r:current)if(r.objectKey().equals(selected.objectKey())){if(!r.equals(selected))throw failure();found=true;}
        if(!found)result.add(selected);return result;
    }
    private Map<String,String> identities(List<ErasureRecord> records) {
        var result=new TreeMap<String,String>();for(var r:records)if(result.put(r.objectKey(),ErasureCompletion.digest(r))!=null)throw failure();return result;
    }
    private List<String> keys(ErasureRecord r,ErasureCompletion c){return List.of(r.objectKey(),"catalogue-v1/"+realm+"/"+r.withdrawalKey()+".bin",c.objectKey());}
    void verifyRemaining(CheckpointedErasureLedger.Head head) {
        try {
            requireLease.run();var cp=head.checkpoint();
            if(!realm.equals(cp.realm()) || !epoch.equals(cp.databaseEpoch()))throw failure();
            var intents=ledger.intentsAtMost(100000);var catalogues=ledger.catalogueAtMost(100000);
            var values=identities(intents);
            if(values.size()!=cp.count() || !values.equals(identities(catalogues)) || !cp.inventoryDigest(values).equals(cp.inventorySha256()))throw failure();
            var known=new HashMap<String,ErasureRecord>();for(var r:intents)if(known.put(r.withdrawalKey(),r)!=null)throw failure();
            for(String key:objects.list("completion-v1/"+realm+"/",100000)) {
                var receipt=json.readValue(cipher.decryptDocument(realm,key,objects.read(key)),ErasureCompletion.class);
                var owner=known.get(receipt.withdrawalKey());
                if(owner==null || !receipt.objectKey().equals(key) || !receipt.intentDigest().equals(ErasureCompletion.digest(owner)))throw failure();
            }
        }catch(Exception ignored){throw failure();}
    }
    @Override public Map<String,String> inventory() {
        requireLease.run();var result=new TreeMap<String,String>();
        for(String prefix:List.of("v1/","catalogue-v1/","completion-v1/"))
            for(String key:objects.list(prefix+realm+"/",100000)) {
                byte[] bytes=objects.read(key);if(bytes==null || result.put(key,ErasureCheckpoint.hash(bytes))!=null)throw failure();
            }
        return result;
    }
    @Override public void removeExact(String key,String hash,boolean retry){requireLease.run();objects.removeExact(key,hash,retry);}
    private static IllegalStateException failure(){return new IllegalStateException("RETIREMENT_CONTEXT_INVALID");}
}
