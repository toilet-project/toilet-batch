import com.example.toiletbatch.account.RetentionFailureNotice;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** One explicitly approved test notice; no URL in command arguments, output or result files. */
class RetentionDiscordSmoke {
    public static void main(String[] args) {
        String stage="preflight";
        try {
            if(args.length!=1)throw new IllegalArgumentException();
            Path root=Path.of(args[0]).toAbsolutePath().normalize();
            if(!root.toString().startsWith("/tmp/geupddong-retention-check.")||!root.equals(root.toRealPath())
                    ||!Files.readString(root.resolve("SYNTHETIC_ONLY")).equals("retention-rehearsal-v1\n"))throw new IllegalStateException();
            String url=null;
            try(var lines=Files.lines(Path.of("/home/luha/toilet-batch/.env"))) {
                for(var iterator=lines.iterator();iterator.hasNext();) {
                    String line=iterator.next();
                    if(line.startsWith("BATCH_FAILURE_WEBHOOK_URL=")){
                        if(url!=null)throw new IllegalStateException();
                        url=line.substring("BATCH_FAILURE_WEBHOOK_URL=".length()).strip();
                    }
                }
            }
            var transport=RetentionFailureNotice.discord(Objects.requireNonNull(url));
            // Never resend automatically after an uncertain network outcome.
            Files.writeString(root.resolve("discord-test-attempted"),"ONE_APPROVED_ATTEMPT\n",StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(root.resolve("discord-test-attempted"),PosixFilePermissions.fromString("rw-------"));
            stage="delivery";
            transport.send(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "content","[테스트 · 급똥] 백업 만료 점검 알림 경로 확인입니다.\nLinux 격리 검증 15건 통과 · 가상 백업만 사용 · 운영 백업/DB 변경 없음.\n실제 장애가 아니며, 승인받은 테스트 메시지 1건입니다.",
                    "allowed_mentions",Map.of("parse",List.of()))));
            Files.writeString(root.resolve("discord-test-accepted"),"DISCORD_HTTP_2XX\n",StandardOpenOption.CREATE_NEW);
            System.out.println("DISCORD_SMOKE_HTTP_2XX messages=1");
        }catch(Exception ignored){System.err.println("DISCORD_SMOKE_FAILED stage="+stage+" automaticRetry=false");System.exit(1);}
    }
}
