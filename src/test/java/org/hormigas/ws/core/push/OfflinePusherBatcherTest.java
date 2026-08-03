package org.hormigas.ws.core.push;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hormigas.ws.support.TestMetrics;
import io.smallrye.mutiny.Uni;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.ws.domain.push.OfflineEvent;
import org.hormigas.ws.ports.push.OfflineEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the thin offline-push facade over its {@link org.hormigas.hl.batch.coalescing.CoalescingBatcher}:
 * {@link OfflinePusherBatcher#push} feeds the coalescing window and, once the real linger fires,
 * one publish per unique {@code (recipient, conversation)} runs. Fields are wired directly (no CDI, same
 * pattern as {@code DelivererTest}); the publisher port is mocked and a real {@link SimpleMeterRegistry}
 * backs the meters. The coalescing primitive itself is exhaustively covered by {@code CoalescingBatcherTest}.
 */
@DisplayName("OfflinePusherBatcher — coalesce per (recipient, conversation) → one publish, fire-and-forget")
class OfflinePusherBatcherTest {

    private OfflinePusherBatcher build(OfflineEventPublisher publisher, boolean enabled) {
        MessengerConfig config = mock(MessengerConfig.class);
        MessengerConfig.OfflinePush on = mock(MessengerConfig.OfflinePush.class);
        when(on.enabled()).thenReturn(enabled);
        when(on.topic()).thenReturn("chat.offline.notify");
        when(on.maxSize()).thenReturn(64);
        when(on.lingerMs()).thenReturn(50);
        when(on.maxConcurrentBatches()).thenReturn(4);
        when(config.offlinePush()).thenReturn(on);
        MessengerConfig.StreamRetry sr = mock(MessengerConfig.StreamRetry.class);
        when(sr.minBackoffMs()).thenReturn(200);
        when(sr.maxBackoffMs()).thenReturn(5000);
        when(config.streamRetry()).thenReturn(sr);

        OfflinePusherBatcher batcher = new OfflinePusherBatcher();
        batcher.publisher = publisher;
        batcher.config = config;
        batcher.metrics = new TestMetrics();
        batcher.init();
        return batcher;
    }

    private OfflineEvent event(String recipient, String conversation) {
        return new OfflineEvent(recipient, "evt", "sender", null, conversation, "01M", "hi", "text",
                "2026-07-21T16:00:00Z");
    }

    @Test
    @DisplayName("N events for the same (recipient, conversation) in a window coalesce to one publish")
    void coalescesSameKeyToOnePublish() {
        OfflineEventPublisher publisher = mock(OfflineEventPublisher.class);
        when(publisher.publish(any())).thenReturn(Uni.createFrom().voidItem());
        OfflinePusherBatcher batcher = build(publisher, true);

        batcher.push(event("recipient-1", "conv-1"));
        batcher.push(event("recipient-1", "conv-1"));
        batcher.push(event("recipient-1", "conv-1"));

        // Real linger drives the flush; a generous timeout tolerates a slow (jacoco/CI) build.
        verify(publisher, timeout(15_000).times(1)).publish(any());
    }

    @Test
    @DisplayName("two distinct (recipient, conversation) keys each publish once")
    void distinctKeysEachPublish() {
        OfflineEventPublisher publisher = mock(OfflineEventPublisher.class);
        when(publisher.publish(any())).thenReturn(Uni.createFrom().voidItem());
        OfflinePusherBatcher batcher = build(publisher, true);

        batcher.push(event("recipient-1", "conv-1"));
        batcher.push(event("recipient-2", "conv-2"));

        verify(publisher, timeout(15_000).times(2)).publish(any());
    }

    @Test
    @DisplayName("disabled → push is a pure no-op (publisher never touched)")
    void disabledIsNoOp() {
        OfflineEventPublisher publisher = mock(OfflineEventPublisher.class);
        OfflinePusherBatcher batcher = build(publisher, false);

        assertDoesNotThrow(() -> batcher.push(event("recipient-1", "conv-1")));

        verify(publisher, after(500).never()).publish(any());
    }

    @Test
    @DisplayName("closed buffer (post-drain) sheds without throwing into the caller")
    void closedBufferShedsWithoutThrowing() {
        OfflineEventPublisher publisher = mock(OfflineEventPublisher.class);
        when(publisher.publish(any())).thenReturn(Uni.createFrom().voidItem());
        OfflinePusherBatcher batcher = build(publisher, true);

        batcher.drain(Duration.ofSeconds(2)); // complete the stream (empty window)

        // Fire-and-forget contract: enqueue after close is shed, never rethrown into the delivery path.
        assertDoesNotThrow(() -> batcher.push(event("recipient-1", "conv-1")));
        verify(publisher, after(500).never()).publish(any());
    }

    @Test
    @DisplayName("an event with no recipient is shed silently (cannot be keyed/partitioned)")
    void noRecipientShed() {
        OfflineEventPublisher publisher = mock(OfflineEventPublisher.class);
        OfflinePusherBatcher batcher = build(publisher, true);

        assertDoesNotThrow(() -> batcher.push(event(null, "conv-1")));

        verify(publisher, after(500).never()).publish(any());
    }
}
