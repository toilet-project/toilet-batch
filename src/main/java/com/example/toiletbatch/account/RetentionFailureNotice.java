package com.example.toiletbatch.account;

import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.Map;

/** Aggregate-only notices. State advances only AFTER delivery succeeds. */
public final class RetentionFailureNotice {
    public record State(int version, String fingerprint, String sentAt, boolean failure) {
        public State {
            if(version!=1 || !fingerprint.matches("[A-Z_]+:[0-9]+"))throw new IllegalArgumentException("NOTICE_STATE_INVALID");
            Instant.parse(sentAt);
        }
    }
    public interface Store { State read() throws Exception; void write(State state) throws Exception; }
    @FunctionalInterface public interface Transport { void send(String body) throws Exception; }
    private final Store store; private final Transport transport;
    public RetentionFailureNotice(Store store, Transport transport){this.store=store;this.transport=transport;}

    public boolean notify(BackupRetentionMaintenance.Status status,int expired,Instant now)throws Exception{
        if(expired<0)throw new IllegalArgumentException();
        boolean failure=status!=BackupRetentionMaintenance.Status.NO_EXPIRED;
        String fingerprint=status.name()+":"+expired;
        State previous=store.read();
        if(previous!=null && now.isBefore(Instant.parse(previous.sentAt())))throw new IllegalStateException("NOTICE_CLOCK_REVERSED");
        if(!failure && (previous==null || !previous.failure()))return false;
        if(failure && previous!=null && previous.fingerprint().equals(fingerprint)
                && now.isBefore(Instant.parse(previous.sentAt()).plus(Duration.ofHours(6))))return false;
        String text="[급똥] 백업 만료 점검 "+(failure?"확인 필요":"정상 복구")+"\n코드: "+status.name()+"\n만료 후보: "+expired;
        String payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("content",text,"allowed_mentions",Map.of("parse",java.util.List.of())));
        transport.send(payload);
        store.write(new State(1,fingerprint,now.toString(),failure));
        return true;
    }
    public static Transport discord(String value){
        URI uri=URI.create(value);
        if(!"https".equals(uri.getScheme()) || !"discord.com".equals(uri.getHost()) || uri.getPort()!=-1
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !uri.getPath().matches("/api/webhooks/[0-9]+/[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("WEBHOOK_INVALID");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return body->{
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
            if(status<200 || status>=300)throw new IllegalStateException("NOTICE_DELIVERY_FAILED");
        };
    }
}
