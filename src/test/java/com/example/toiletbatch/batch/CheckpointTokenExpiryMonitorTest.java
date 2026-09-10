package com.example.toiletbatch.batch;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckpointTokenExpiryMonitorTest {
    final Instant now=Instant.parse("2030-01-01T00:00:00Z");
    @Test void boundaries() {
        assertEquals("UNKNOWN",CheckpointTokenExpiryMonitor.level(null,now));
        assertEquals("EXPIRED",CheckpointTokenExpiryMonitor.level(now,now));
        for(int day:new int[]{1,7,14,30}) assertEquals("DAY_"+day,CheckpointTokenExpiryMonitor.level(now.plus(Duration.ofDays(day)),now));
        assertEquals("HEALTHY",CheckpointTokenExpiryMonitor.level(now.plus(Duration.ofDays(30)).plusSeconds(1),now));
    }
    @Test void strictHeaderParsing() {
        assertEquals(Instant.parse("2026-10-06T16:10:27Z"),CheckpointTokenExpiryMonitor.parseExpiry("2026-10-06 16:10:27 UTC"));
        assertNull(CheckpointTokenExpiryMonitor.parseExpiry("2026-02-30 00:00:00 UTC"));
        assertNull(CheckpointTokenExpiryMonitor.parseExpiry(null));
        assertNull(CheckpointTokenExpiryMonitor.parseExpiry("2026-10-06 16:10:27 PST"));
    }
    @Test void sendsOncePerThresholdOnlyAfterSuccess() {
        var notifier=mock(BatchFailureNotifier.class);
        when(notifier.notifyCheckpointTokenExpiry("DAY_30")).thenReturn(false,true);
        var monitor=new CheckpointTokenExpiryMonitor(notifier,()->now.plus(Duration.ofDays(20)));
        monitor.check(now); monitor.check(now); monitor.check(now);
        verify(notifier,times(2)).notifyCheckpointTokenExpiry("DAY_30");
    }
    @Test void probeFailureIsSanitizedAndRetriesDaily() {
        var notifier=mock(BatchFailureNotifier.class);
        when(notifier.notifyCheckpointTokenExpiry("UNKNOWN")).thenReturn(true);
        var monitor=new CheckpointTokenExpiryMonitor(notifier,()->{throw new RuntimeException("secret");});
        monitor.check(now); monitor.check(now); monitor.check(now.plus(Duration.ofDays(1)));
        verify(notifier,times(2)).notifyCheckpointTokenExpiry("UNKNOWN");
    }
    @Test void healthyDoesNotSend() {
        var notifier=mock(BatchFailureNotifier.class);
        new CheckpointTokenExpiryMonitor(notifier,()->now.plus(Duration.ofDays(40))).check(now);
        verifyNoInteractions(notifier);
    }
    @Test void nextThresholdIsNotSuppressed() {
        var notifier=mock(BatchFailureNotifier.class);
        when(notifier.notifyCheckpointTokenExpiry(anyString())).thenReturn(true);
        var monitor=new CheckpointTokenExpiryMonitor(notifier,()->now.plus(Duration.ofDays(20)));
        monitor.check(now); monitor.check(now.plus(Duration.ofDays(6)));
        verify(notifier).notifyCheckpointTokenExpiry("DAY_30");
        verify(notifier).notifyCheckpointTokenExpiry("DAY_14");
    }
    @Test void notifierUsesExistingWebhookAndNoMentions() {
        var builder=org.springframework.web.client.RestClient.builder();
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://discord.com/api/webhooks/1/fake"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("User-Agent","DiscordBot (https://geupddong.com, 1.0)"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("allowed_mentions")))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent());
        var notifier=new BatchFailureNotifier(builder,new BatchNotificationProperties("https://discord.com/api/webhooks/1/fake"),null);
        assertTrue(notifier.notifyCheckpointTokenExpiry("DAY_30")); server.verify();
    }
    @Test void missingWebhookDoesNotAcknowledgeDelivery() {
        var notifier=new BatchFailureNotifier(org.springframework.web.client.RestClient.builder(),new BatchNotificationProperties(""),null);
        assertFalse(notifier.notifyCheckpointTokenExpiry("DAY_1"));
    }

    @Test void monitorIsNotRegisteredByDefault() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(CheckpointTokenExpiryMonitor.class);
            context.refresh();
            assertTrue(context.getBeansOfType(CheckpointTokenExpiryMonitor.class).isEmpty());
        }
    }

    @Test void enabledMonitorUsesExistingNotifierBeanWithoutNetworkAtStartup() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",java.util.Map.of("batch.checkpoint-token-monitor.enabled","true")));
            context.registerBean(BatchFailureNotifier.class,()->mock(BatchFailureNotifier.class));
            context.register(CheckpointTokenExpiryMonitor.class); context.refresh();
            assertNotNull(context.getBean(CheckpointTokenExpiryMonitor.class));
        }
    }

    @Test void failedDiscordResponseDoesNotAcknowledgeDelivery() {
        var builder=org.springframework.web.client.RestClient.builder();
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://discord.com/api/webhooks/1/fake"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
        var notifier=new BatchFailureNotifier(builder,new BatchNotificationProperties("https://discord.com/api/webhooks/1/fake"),null);
        assertFalse(notifier.notifyCheckpointTokenExpiry("DAY_30")); server.verify();
    }
}
