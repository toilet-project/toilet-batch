package com.geupddong.account;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Compatible normal checkpoint store with proved, repeatable retirement transitions.
 * Legacy checkpoint records remain byte-for-byte unchanged. Old readers reject the new records.
 * Phase 7 commits the decreased inventory; phase 8 acknowledges journal cleanup and unblocks writers.
 */
public final class GitHubErasureHistoryStore implements CheckpointedErasureLedger.Store {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern CHECKPOINT=Pattern.compile("checkpoints/checkpoint-([0-9]{20})\\.json");
    private static final Pattern RETIREMENT=Pattern.compile("checkpoints/retirement-([0-9]{20})-phase-([0-8])\\.json");
    private static final Pattern REVIEW=Pattern.compile("checkpoints/retirement-([0-9]{20})-review-([0-9]{4})\\.json");
    public record Binding(String realm,String epoch,String operationId,String baseRevision,String checkpointDigest,
                          long checkpointSequence,int beforeCount,String afterInventoryDigest,String journalDigest,
                          String planDigest,String preparedAt,String validUntil) {
        public Binding {
            try {
                if(realm==null || !realm.matches("[a-z0-9-]{3,40}") || !uuid(epoch) || !uuid(operationId)
                        || !sha(baseRevision) || !hash(checkpointDigest) || !hash(afterInventoryDigest)
                        || !hash(journalDigest) || !hash(planDigest) || checkpointSequence<1 || checkpointSequence>=100000
                        || beforeCount<1 || beforeCount>100000) throw failure();
                Instant from=Instant.parse(preparedAt),until=Instant.parse(validUntil);
                if(!from.toString().equals(preparedAt) || !until.toString().equals(validUntil)
                        || until.isBefore(from) || until.isAfter(from.plusSeconds(600))) throw failure();
            } catch(RuntimeException ignored) {throw failure();}
        }
    }
    public record Phase(int version,int phase,Binding binding,String previousPhaseDigest,String recordedAt,int reviewSequence,String authorizationUntil) {
        public Phase(int version,int phase,Binding binding,String previousPhaseDigest,String recordedAt) {
            this(version,phase,binding,previousPhaseDigest,recordedAt,0,binding.validUntil());
        }
        public Phase {
            try {
                if(version!=2 || phase<0 || phase>8 || binding==null || reviewSequence<0 || reviewSequence>9999
                        || (phase==0 ? !"".equals(previousPhaseDigest) : !hash(previousPhaseDigest))) throw failure();
                Instant at=Instant.parse(recordedAt);
                // Cleanup may safely occur after an outage. It never authorizes another member deletion.
                if(!at.toString().equals(recordedAt) || !Instant.parse(authorizationUntil).toString().equals(authorizationUntil)
                        || at.isBefore(Instant.parse(binding.preparedAt()))
                        || (reviewSequence==0 && !authorizationUntil.equals(binding.validUntil()))
                        || (phase==0 && reviewSequence!=0) || (phase<8 && at.isAfter(Instant.parse(authorizationUntil)))) throw failure();
            } catch(RuntimeException ignored) {throw failure();}
        }
        public byte[] bytes() {return encode(this);}
        public String digest() {return ErasureCheckpoint.hash(bytes());}
    }
    public record Review(int version,long checkpointSequence,int sequence,int phase,String phaseDigest,String journalDigest,
                         String reviewedAt,String validUntil,String evidenceDigest,String previousReviewDigest) {
        public Review {
            try {
                var at=Instant.parse(reviewedAt);var until=Instant.parse(validUntil);
                if(version!=2 || checkpointSequence<1 || checkpointSequence>=100000 || sequence<1 || sequence>9999 || phase<0 || phase>6
                        || !hash(phaseDigest) || !hash(journalDigest) || !hash(evidenceDigest)
                        || (sequence==1?!"".equals(previousReviewDigest):!hash(previousReviewDigest))
                        || !at.toString().equals(reviewedAt) || !until.toString().equals(validUntil)
                        || until.isBefore(at) || until.isAfter(at.plusSeconds(600)))throw failure();
            }catch(RuntimeException ignored){throw failure();}
        }
        public byte[] bytes(){return encode(this);}
        public String digest(){return ErasureCheckpoint.hash(bytes());}
    }
    public record View(CheckpointedErasureLedger.Head head,List<Phase> latest,List<Review> reviews) {
        public View {latest=List.copyOf(latest);reviews=List.copyOf(reviews);}
        public boolean pending() {return !latest.isEmpty() && latest.getLast().phase()<8;}
        public int reviewSequence(){return reviews.isEmpty()?0:reviews.getLast().sequence();}
        public String authorizedUntil(){return reviews.isEmpty()?latest.getLast().binding().validUntil():reviews.getLast().validUntil();}
    }
    private final GitHubErasureCheckpointStore.Transport transport;
    private final Clock clock;
    // Cache only immutable Git blobs, never the branch head or interpreted authorization.
    private final Map<String,byte[]> blobCache=new LinkedHashMap<>(256,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,byte[]> e){return size()>2048;}
    };
    public GitHubErasureHistoryStore(GitHubErasureCheckpointStore.Transport transport,Clock clock) {
        this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);
    }
    public static GitHubErasureHistoryStore configured(String token) {
        return new GitHubErasureHistoryStore(GitHubErasureCheckpointStore.configuredTransport(token),Clock.systemUTC());
    }
    private JsonNode call(String method,String path,Object body) {
        try {return Objects.requireNonNull(transport.request(method,path,body));} catch(RuntimeException e) {throw failure();}
    }
    private static String gitSha(JsonNode node) {String value=node.asText();if(!sha(value))throw failure();return value;}
    private byte[] blob(String sha,int limit) {
        try {
            synchronized(blobCache) {
                byte[] known=blobCache.get(sha);
                if(known!=null){if(known.length>limit)throw failure();return known.clone();}
            }
            var node=call("GET","/git/blobs/"+sha,null);int size=node.path("size").asInt(-1);
            if(!"base64".equals(node.path("encoding").asText()) || size<1 || size>limit)throw failure();
            byte[] bytes=Base64.getMimeDecoder().decode(node.path("content").asText());
            if(bytes.length!=size || bytes.length>limit)throw failure();
            synchronized(blobCache){blobCache.put(sha,bytes.clone());}return bytes;
        }catch(RuntimeException ignored){throw failure();}
    }
    public View inspect() {
        try {return inspectChecked();}catch(Exception ignored){throw failure();}
    }
    private View inspectChecked() throws Exception {
        String revision=gitSha(call("GET","/git/ref/heads/main",null).path("object").path("sha"));
        var tree=call("GET","/git/trees/"+revision+"?recursive=1",null);
        if(!tree.has("truncated") || tree.path("truncated").asBoolean(true) || !tree.path("tree").isArray()
                || tree.path("tree").size()>1000000)throw failure();
        var checkpoints=new TreeMap<Long,ErasureCheckpoint>();
        var jobs=new TreeMap<Long,TreeMap<Integer,Phase>>();
        var reviews=new TreeMap<Long,TreeMap<Integer,Review>>();
        for(var node:tree.path("tree")) {
            String path=node.path("path").asText();
            if(!path.startsWith("checkpoints/") || path.equals("checkpoints/.gitkeep"))continue;
            if(!"blob".equals(node.path("type").asText()) || !"100644".equals(node.path("mode").asText()))throw failure();
            var cpMatch=CHECKPOINT.matcher(path);var jobMatch=RETIREMENT.matcher(path);var reviewMatch=REVIEW.matcher(path);
            if(cpMatch.matches()) {
                long number=Long.parseLong(cpMatch.group(1));byte[] bytes=blob(gitSha(node.path("sha")),1024);
                var cp=JSON.readValue(bytes,ErasureCheckpoint.class);
                if(number<1 || number>100000 || cp.sequence()!=number || !Arrays.equals(bytes,cp.bytes())
                        || checkpoints.put(number,cp)!=null)throw failure();
            }else if(jobMatch.matches()) {
                long number=Long.parseLong(jobMatch.group(1));int phase=Integer.parseInt(jobMatch.group(2));
                byte[] bytes=blob(gitSha(node.path("sha")),4096);Phase p=JSON.readValue(bytes,Phase.class);
                if(number<1 || number>=100000 || p.phase()!=phase || p.binding().checkpointSequence()!=number
                        || !Arrays.equals(bytes,p.bytes()) || jobs.computeIfAbsent(number,k->new TreeMap<>()).put(phase,p)!=null)throw failure();
            }else if(reviewMatch.matches()) {
                long number=Long.parseLong(reviewMatch.group(1));int sequence=Integer.parseInt(reviewMatch.group(2));
                byte[] bytes=blob(gitSha(node.path("sha")),2048);Review r=JSON.readValue(bytes,Review.class);
                if(r.checkpointSequence()!=number || r.sequence()!=sequence || !Arrays.equals(bytes,r.bytes())
                        || reviews.computeIfAbsent(number,k->new TreeMap<>()).put(sequence,r)!=null)throw failure();
            }else throw failure();
        }
        if(checkpoints.isEmpty() || checkpoints.lastKey()!=checkpoints.size())throw failure();
        var operations=new HashSet<String>();
        if(!jobs.keySet().containsAll(reviews.keySet()))throw failure();
        for(var job:jobs.entrySet()) {
            var base=checkpoints.get(job.getKey());var phases=job.getValue();
            if(base==null || phases.firstKey()!=0 || phases.lastKey()!=phases.size()-1)throw failure();
            Binding binding=phases.firstEntry().getValue().binding();
            if(!operations.add(binding.operationId()) || !binding.realm().equals(base.realm()) || !binding.epoch().equals(base.databaseEpoch())
                    || !binding.checkpointDigest().equals(base.digest()) || binding.beforeCount()!=base.count()
                    || Instant.parse(binding.preparedAt()).isBefore(Instant.parse(base.recordedAt())))throw failure();
            Phase previous=null;
            var renewals=reviews.getOrDefault(job.getKey(),new TreeMap<>());Review lastReview=null;
            if(!renewals.isEmpty() && (renewals.firstKey()!=1 || renewals.lastKey()!=renewals.size()))throw failure();
            for(Review r:renewals.values()) {
                Phase observed=phases.get(r.phase());
                if(observed==null || !r.phaseDigest().equals(observed.digest()) || !r.journalDigest().equals(binding.journalDigest())
                        || Instant.parse(r.reviewedAt()).isBefore(Instant.parse(observed.recordedAt()))
                        || Instant.parse(r.reviewedAt()).isAfter(clock.instant()))throw failure();
                if(lastReview!=null && (!r.previousReviewDigest().equals(lastReview.digest()) || r.phase()<lastReview.phase()
                        || Instant.parse(r.reviewedAt()).isBefore(Instant.parse(lastReview.reviewedAt()))))throw failure();
                lastReview=r;
            }
            for(Phase phase:phases.values()) {
                if(!phase.binding().equals(binding) || Instant.parse(phase.recordedAt()).isAfter(clock.instant()))throw failure();
                if(phase.reviewSequence()>0) {
                    Review r=renewals.get(phase.reviewSequence());
                    if(r==null || r.phase()>=phase.phase() || !r.validUntil().equals(phase.authorizationUntil())
                            || Instant.parse(r.reviewedAt()).isAfter(Instant.parse(phase.recordedAt())))throw failure();
                }
                if(previous!=null && (!phase.previousPhaseDigest().equals(previous.digest())
                        || phase.reviewSequence()<previous.reviewSequence()
                        || Instant.parse(phase.recordedAt()).isBefore(Instant.parse(previous.recordedAt()))))throw failure();
                previous=phase;
            }
            if(phases.lastKey()<7 && checkpoints.lastKey()!=base.sequence())throw failure();
            if(phases.lastKey()==7 && checkpoints.lastKey()!=base.sequence()+1)throw failure();
            if(phases.lastKey()<8 && !job.getKey().equals(jobs.lastKey()))throw failure();
            if(phases.lastKey()>=7) {
                var committed=checkpoints.get(base.sequence()+1);Phase p=phases.get(7);
                if(committed==null || committed.count()!=base.count()-1
                        || !committed.inventorySha256().equals(binding.afterInventoryDigest())
                        || !committed.recordedAt().equals(p.recordedAt()))throw failure();
            }
        }
        ErasureCheckpoint previous=null;
        for(var cp:checkpoints.values()) {
            if(Instant.parse(cp.recordedAt()).isAfter(clock.instant()))throw failure();
            if(previous==null) {if(!cp.previousCheckpointSha256().isEmpty())throw failure();}
            else {
                if(!cp.realm().equals(previous.realm()) || !cp.databaseEpoch().equals(previous.databaseEpoch())
                        || !cp.previousCheckpointSha256().equals(previous.digest())
                        || Instant.parse(cp.recordedAt()).isBefore(Instant.parse(previous.recordedAt())))throw failure();
                var job=jobs.get(previous.sequence());
                if(job!=null) {if(job.lastKey()<7 || cp.count()!=previous.count()-1)throw failure();}
                else if(cp.count()!=previous.count()+1)throw failure();
            }
            previous=cp;
        }
        return new View(new CheckpointedErasureLedger.Head(revision,checkpoints.lastEntry().getValue()),
                jobs.isEmpty()?List.of():new ArrayList<>(jobs.lastEntry().getValue().values()),
                jobs.isEmpty()?List.of():new ArrayList<>(reviews.getOrDefault(jobs.lastKey(),new TreeMap<>()).values()));
    }
    @Override public CheckpointedErasureLedger.Head read() {
        View view=inspect();if(view.pending())throw failure();return view.head();
    }
    @Override public void append(CheckpointedErasureLedger.Head expected,ErasureCheckpoint next) {
        var view=inspect();var previous=view.head().checkpoint();
        if(view.pending() || !view.head().equals(expected) || next.sequence()!=previous.sequence()+1
                || next.count()!=previous.count()+1 || !next.previousCheckpointSha256().equals(previous.digest())
                || !next.realm().equals(previous.realm()) || !next.databaseEpoch().equals(previous.databaseEpoch())
                || Instant.parse(next.recordedAt()).isBefore(Instant.parse(previous.recordedAt()))
                || Instant.parse(next.recordedAt()).isAfter(clock.instant()))throw failure();
        write(expected.revision(),Map.of(checkpointPath(next.sequence()),next.bytes()));
        if(!read().checkpoint().equals(next))throw failure();
    }
    public void appendPhase(String expectedRevision,Phase next) {
        View before=inspect();var cp=before.head().checkpoint();Binding b=next.binding();
        if(!before.head().revision().equals(expectedRevision) || Instant.parse(next.recordedAt()).isAfter(clock.instant()))throw failure();
        if(next.phase()==0) {
            if(before.pending() || !b.baseRevision().equals(expectedRevision) || b.checkpointSequence()!=cp.sequence()
                    || !b.checkpointDigest().equals(cp.digest()) || b.beforeCount()!=cp.count()
                    || !b.realm().equals(cp.realm()) || !b.epoch().equals(cp.databaseEpoch())
                    || Instant.parse(b.preparedAt()).isBefore(Instant.parse(cp.recordedAt())))throw failure();
        } else {
            if(before.latest().isEmpty())throw failure();Phase previous=before.latest().getLast();
            if(previous.phase()+1!=next.phase() || !previous.binding().equals(b)
                    || next.reviewSequence()!=before.reviewSequence() || !next.authorizationUntil().equals(before.authorizedUntil())
                    || !next.previousPhaseDigest().equals(previous.digest())
                    || Instant.parse(next.recordedAt()).isBefore(Instant.parse(previous.recordedAt())))throw failure();
        }
        var files=new TreeMap<String,byte[]>();files.put(phasePath(b.checkpointSequence(),next.phase()),next.bytes());
        if(next.phase()==7) {
            if(cp.sequence()!=b.checkpointSequence() || !cp.digest().equals(b.checkpointDigest()))throw failure();
            var committed=new ErasureCheckpoint(1,cp.realm(),cp.databaseEpoch(),cp.sequence()+1,cp.count()-1,
                    b.afterInventoryDigest(),cp.digest(),next.recordedAt());
            files.put(checkpointPath(committed.sequence()),committed.bytes());
        }
        write(expectedRevision,files);
        View after=inspect();if(!after.latest().getLast().equals(next))throw failure();
    }
    public void appendReview(String expectedRevision,Review review) {
        View before=inspect();if(!before.head().revision().equals(expectedRevision) || before.latest().isEmpty())throw failure();
        Phase phase=before.latest().getLast();
        if(phase.phase()>6 || review.phase()!=phase.phase() || !review.phaseDigest().equals(phase.digest())
                || review.checkpointSequence()!=phase.binding().checkpointSequence() || !review.journalDigest().equals(phase.binding().journalDigest())
                || review.sequence()!=before.reviewSequence()+1 || !review.previousReviewDigest().equals(before.reviews().isEmpty()?"":before.reviews().getLast().digest())
                || Instant.parse(review.reviewedAt()).isBefore(Instant.parse(phase.recordedAt()))
                || Instant.parse(review.reviewedAt()).isAfter(clock.instant())
                || (!before.reviews().isEmpty() && Instant.parse(review.reviewedAt()).isBefore(Instant.parse(before.reviews().getLast().reviewedAt()))))throw failure();
        write(expectedRevision,Map.of(reviewPath(review.checkpointSequence(),review.sequence()),review.bytes()));
        if(!inspect().reviews().getLast().equals(review))throw failure();
    }
    private void write(String parent,Map<String,byte[]> files) {
        String baseTree=gitSha(call("GET","/git/commits/"+parent,null).path("tree").path("sha"));var entries=new ArrayList<Object>();
        files.forEach((path,bytes)->{
            String blob=gitSha(call("POST","/git/blobs",Map.of("encoding","base64","content",Base64.getEncoder().encodeToString(bytes))).path("sha"));
            entries.add(Map.of("path",path,"mode","100644","type","blob","sha",blob));
        });
        String tree=gitSha(call("POST","/git/trees",Map.of("base_tree",baseTree,"tree",entries)).path("sha"));
        String revision=gitSha(call("POST","/git/commits",Map.of("message","Record verified erasure history",
                "tree",tree,"parents",List.of(parent))).path("sha"));
        call("PATCH","/git/refs/heads/main",Map.of("sha",revision,"force",false));
        if(!inspect().head().revision().equals(revision))throw failure();
    }
    public static String checkpointPath(long sequence){return String.format(Locale.ROOT,"checkpoints/checkpoint-%020d.json",sequence);}
    public static String phasePath(long sequence,int phase){return String.format(Locale.ROOT,"checkpoints/retirement-%020d-phase-%d.json",sequence,phase);}
    public static String reviewPath(long sequence,int review){return String.format(Locale.ROOT,"checkpoints/retirement-%020d-review-%04d.json",sequence,review);}
    private static byte[] encode(Object object){try{return JSON.writeValueAsBytes(object);}catch(Exception e){throw failure();}}
    private static boolean uuid(String s){try{return UUID.fromString(s).toString().equals(s);}catch(Exception e){return false;}}
    private static boolean sha(String s){return s!=null && s.matches("[a-f0-9]{40}");}
    private static boolean hash(String s){return s!=null && s.matches("[a-f0-9]{64}");}
    private static IllegalStateException failure(){return new IllegalStateException("ERASURE_HISTORY_UNAVAILABLE");}
}
