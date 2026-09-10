package com.example.toiletbatch.batch;

import java.net.http.HttpClient;
import java.time.*;
import java.time.format.*;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Read-only token check within the existing batch process and Discord notifier.
 * No database, account job or external ledger writes. Disabled until deployment approval.
 */
@Component
@ConditionalOnProperty(name="batch.checkpoint-token-monitor.enabled", havingValue="true")
public class CheckpointTokenExpiryMonitor {
    private final BatchFailureNotifier notifier;
    private final Supplier<Instant> probe;
    private String lastSent;

    @org.springframework.beans.factory.annotation.Autowired
    public CheckpointTokenExpiryMonitor(BatchFailureNotifier notifier,
            @Value("${ERASURE_CHECKPOINT_GITHUB_TOKEN:}") String token) {
        this(notifier, probe(token));
    }

    CheckpointTokenExpiryMonitor(BatchFailureNotifier notifier, Supplier<Instant> probe) {
        this.notifier=notifier; this.probe=probe;
    }

    static Supplier<Instant> probe(String token) {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(15));
        var rest=RestClient.builder().requestFactory(factory).build();
        return () -> {
            if(token==null || token.isBlank()) return null;
            var response=rest.get().uri("https://api.github.com/repos/toilet-project/operations-checkpoints")
                    .header("Authorization","Bearer "+token)
                    .header("Accept","application/vnd.github+json")
                    .header("X-GitHub-Api-Version","2022-11-28")
                    .header("User-Agent","geupddong-checkpoint-expiry")
                    .retrieve().toBodilessEntity();
            if(response.getStatusCode().value()!=200) return null;
            return parseExpiry(response.getHeaders().getFirst("GitHub-Authentication-Token-Expiration"));
        };
    }

    static Instant parseExpiry(String header) {
        if(header==null || !header.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} UTC")) return null;
        try {
            return LocalDateTime.parse(header,DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'",Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT)).toInstant(ZoneOffset.UTC);
        } catch(DateTimeException ignored) {return null;}
    }

    static String level(Instant expiry, Instant now) {
        if(expiry==null) return "UNKNOWN";
        if(!expiry.isAfter(now)) return "EXPIRED";
        for(int day:new int[]{1,7,14,30}) if(!expiry.isAfter(now.plus(Duration.ofDays(day)))) return "DAY_"+day;
        return "HEALTHY";
    }

    @Scheduled(cron="${batch.checkpoint-token-monitor.cron:0 0 9 * * *}", zone="Asia/Seoul")
    public void checkScheduled() { check(Instant.now()); }

    synchronized void check(Instant now) {
        Instant expiry;
        try {expiry=probe.get();} catch(RuntimeException ignored) {expiry=null;}
        String level=level(expiry,now);
        if(level.equals("HEALTHY")) {lastSent=null; return;}
        // Unknown/expired alert daily. Upcoming expiry alerts once per threshold/expiry.
        // In-memory receipt: restart may repeat an alert, never suppress an unsent alert.
        String fingerprint=level+":"+expiry;
        if(level.equals("UNKNOWN") || level.equals("EXPIRED")) fingerprint+=":"+now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        if(!fingerprint.equals(lastSent) && notifier.notifyCheckpointTokenExpiry(level)) lastSent=fingerprint;
    }
}
