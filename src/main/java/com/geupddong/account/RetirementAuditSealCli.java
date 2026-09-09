package com.geupddong.account;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.StandardEnvironment;

/** Seals an operator-reviewed, complete copy-scope audit; does not discover or assert copy clearance. */
public final class RetirementAuditSealCli {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private RetirementAuditSealCli(){}
    static void seal(Path input,Path output,String realm,String epoch,String scope,String server,
                     ErasureCipher cipher,FileErasureObjectStore.Safety safety,Clock clock,Runnable requireLease) {
        try {
            requireLease.run();
            for(Path path:List.of(input,output)) {
                if(!path.isAbsolute() || !path.equals(path.normalize()))throw failure();
                for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw failure();
                safety.directory(path.getParent());
            }
            if(input.equals(output) || !output.getFileName().toString().equals("retirement-audit.bin"))throw failure();
            safety.file(input.getParent(),input);byte[] plain;
            try(var stream=Files.newInputStream(input,LinkOption.NOFOLLOW_LINKS)){plain=stream.readNBytes(8193);}
            if(plain.length>8192)throw failure();
            var audit=JSON.readValue(plain,RetirementOperationalEvidence.Audit.class);
            var now=clock.instant();var checked=Instant.parse(audit.checkedAt());var keyChecked=Instant.parse(audit.keyRecoveryCheckedAt());
            if(!realm.equals(audit.realm()) || !epoch.equals(audit.epoch()) || !scope.equals(audit.scopeDigest())
                    || !server.equals(audit.serverUuid()) || checked.isAfter(now) || now.isAfter(checked.plusSeconds(600))
                    || keyChecked.isAfter(now) || now.isAfter(keyChecked.plusSeconds(86400))
                    || Instant.parse(audit.lastRestoreAt()).isAfter(checked))throw failure();
            byte[] bytes=cipher.encryptDocument(realm,"retirement-audit-v1/"+realm+"/"+epoch,JSON.writeValueAsBytes(audit));
            // Never repair unknown partial files. An orphan stage file requires separate inspection.
            Path staged=output.resolveSibling("retirement-audit.pending");
            if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)) {
                safety.file(output.getParent(),output);
                RetirementOperationalEvidence.authenticatedFile(output,realm,epoch,cipher,safety).read();
            }
            safety.createFile(staged);
            try(var channel=FileChannel.open(staged,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            safety.file(staged.getParent(),staged);
            if(!Arrays.equals(bytes,Files.readAllBytes(staged)))throw failure();
            Files.move(staged,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            safety.syncDirectory(output.getParent());
            if(!audit.equals(RetirementOperationalEvidence.authenticatedFile(output,realm,epoch,cipher,safety).read()))throw failure();
        }catch(Exception ignored){throw failure();}
    }
    public static void main(String[] args) {
        try {
            if(args.length!=2 || !"--seal-reviewed".equals(args[0]))throw failure();
            var env=new StandardEnvironment();
            if(!env.getProperty("ERASURE_RETIREMENT_AUDIT_REVIEW_APPROVED",Boolean.class,false))throw failure();
            var cipher=new ErasureCipher(env.getRequiredProperty("ERASURE_LEDGER_ACTIVE_KEY_ID"),
                    JSON.readValue(env.getRequiredProperty("ERASURE_LEDGER_KEYS_JSON"),new TypeReference<Map<String,String>>(){}));
            Path lock=Path.of(env.getRequiredProperty("ERASURE_MAINTENANCE_DIRECTORY")).resolve(".maintenance.lock");
            try(var acquired=LocalMaintenanceLease.acquire(lock,1000)) {
                seal(Path.of(args[1]),Path.of(env.getRequiredProperty("ERASURE_RETIREMENT_AUDIT_FILE")),
                        env.getRequiredProperty("ERASURE_LEDGER_REALM"),env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH"),
                        env.getRequiredProperty("ERASURE_RETIREMENT_SCOPE_SHA256"),env.getRequiredProperty("ERASURE_RETIREMENT_SERVER_UUID"),
                        cipher,new FileErasureObjectStore.LinuxSafety(),Clock.systemUTC(),()->{});
            }
            System.out.println("REVIEWED_AUDIT_SEALED: no DB or ledger deletion performed");
        }catch(Exception ignored){System.err.println("RETIREMENT_AUDIT_SEAL_BLOCKED");System.exit(2);}
    }
    private static IllegalStateException failure(){return new IllegalStateException("RETIREMENT_AUDIT_SEAL_BLOCKED");}
}
