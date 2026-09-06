package com.example.toiletbatch.account;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.toiletbatch.account.BackupRetentionMaintenance.Status.*;

class RetentionFailureNoticeTest {
    final Instant now=Instant.parse("2026-09-06T12:00:00Z");
    static class Memory implements RetentionFailureNotice.Store {
        RetentionFailureNotice.State state;
        public RetentionFailureNotice.State read(){return state;}
        public void write(RetentionFailureNotice.State value){state=value;}
    }
    @Test void silentHealthyCooldownAndOneRecovery()throws Exception{
        var store=new Memory();var bodies=new ArrayList<String>();var notice=new RetentionFailureNotice(store,bodies::add);
        assertFalse(notice.notify(NO_EXPIRED,0,now));assertTrue(notice.notify(READY,2,now));
        assertFalse(notice.notify(READY,2,now.plusSeconds(60)));assertTrue(notice.notify(READY,2,now.plus(Duration.ofHours(6))));
        assertTrue(notice.notify(NO_EXPIRED,0,now.plus(Duration.ofHours(7))));assertFalse(notice.notify(NO_EXPIRED,0,now.plus(Duration.ofHours(8))));
        assertEquals(3,bodies.size());assertTrue(bodies.getFirst().contains("allowed_mentions"));
    }
    @Test void deliveryFailureDoesNotAcknowledge()throws Exception{
        var store=new Memory();var failed=new RetentionFailureNotice(store,body->{throw new java.io.IOException();});
        assertThrows(java.io.IOException.class,()->failed.notify(HOLD_UNKNOWN_FILES,0,now));assertNull(store.state);
        assertTrue(new RetentionFailureNotice(store,body->{}).notify(HOLD_UNKNOWN_FILES,0,now));
    }
    @Test void reversedClockAndNegativeCountRejected()throws Exception{
        var store=new Memory();var notice=new RetentionFailureNotice(store,body->{});notice.notify(READY,1,now);
        assertThrows(IllegalStateException.class,()->notice.notify(READY,1,now.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->notice.notify(READY,-1,now));
    }
    @Test void webhookMustBeDiscordHttpsWithoutRedirectOrQuery(){
        assertNotNull(RetentionFailureNotice.discord("https://discord.com/api/webhooks/123/fixture_only"));
        for(String uri:List.of("http://discord.com/api/webhooks/123/x","https://example.com/api/webhooks/123/x","https://discord.com/api/webhooks/123/x?key=x","https://discord.com:443/api/webhooks/123/x"))
            assertThrows(IllegalArgumentException.class,()->RetentionFailureNotice.discord(uri));
    }
}
