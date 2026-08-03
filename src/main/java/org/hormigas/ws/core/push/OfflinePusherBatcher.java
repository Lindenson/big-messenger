package org.hormigas.ws.core.push;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.hl.batch.coalescing.CoalescingBatcher;
import org.hormigas.hl.batch.coalescing.CoalescingBuilder;
import org.hormigas.ws.domain.push.OfflineEvent;
import org.hormigas.ws.ports.push.OfflineEventPublisher;
import org.hormigas.ws.ports.metrics.Metrics;

import java.time.Duration;
import java.util.Collection;
import java.util.function.DoubleConsumer;

/**
 * Offline-recipient notification buffer — a THIN facade that wires a {@link CoalescingBatcher} (the reactive
 * coalescing primitive) to a single driven port, exactly as {@code ReadReceiptBatcher} wires the read-receipt
 * flow. It owns no flow-control of its own: coalescing, linger and the decoupling all live in the batcher's
 * stream. Fed by {@code Deliverer} on the "no live WS session" branch; it never touches the delivery path
 * beyond a non-blocking {@code enqueue}.
 *
 * <p><b>Coalescing.</b> The key is {@code (recipientId, conversationId)} and the merge keeps the LATEST
 * observation — so a burst of undelivered messages to the same offline peer in one conversation collapses to
 * a single push event per short window (the Go service pushes "you have new messages", not one per line).
 *
 * <p><b>Fire-and-forget / shed.</b> {@link #push} delegates to {@link CoalescingBatcher#enqueue},
 * which is non-blocking and drops (sheds, counted) when the window outran the sink or the buffer is draining.
 * A failed publish is logged and dropped by the batcher — the durable outbox is the real guarantee, this push
 * is a courtesy. When {@code enabled=false} the facade is a pure no-op (no batcher wired).
 */
@Slf4j
@Startup
@ApplicationScoped
public class OfflinePusherBatcher implements OfflinePusher {

    /** Coalescing key: one push per offline peer per conversation per window. */
    private record Key(String recipientId, String conversationId) {}

    @Inject
    OfflineEventPublisher publisher;

    @Inject
    MessengerConfig config;

    @Inject
    Metrics metrics;

    private CoalescingBatcher<Key, OfflineEvent> batcher;
    private DoubleConsumer publishErrors;
    private boolean enabled;

    @PostConstruct
    void init() {
        var cfg = config.offlinePush();
        this.enabled = cfg.enabled();
        if (!enabled) {
            log.info("Offline-push publisher DISABLED (processing.messages.offline-push.enabled=false) — no events emitted");
            return;
        }
        publishErrors = metrics.counter("messenger.offline.push.publish.errors",
                "Async offline-push publish failures (dropped; message stays durable in the outbox)");
        batcher = CoalescingBuilder.<Key, OfflineEvent>create()
                .withKey(e -> new Key(e.recipientId(), e.conversationId()))
                .withMerge((a, b) -> b) // keep the latest observation for the (recipient, conversation)
                .withSink(this::sink)
                .withMaxSize(cfg.maxSize()).withLingerMs(cfg.lingerMs()).withConcurrency(cfg.maxConcurrentBatches())
                .withRetryBackoff(config.streamRetry().minBackoffMs(), config.streamRetry().maxBackoffMs())
                .withMetrics(metrics.batch("messenger.offline.push"))
                .build();
        log.info("Offline-push coalescing buffer ready (topic={}, maxSize={}, lingerMs={}, maxConcurrentBatches={})",
                cfg.topic(), cfg.maxSize(), cfg.lingerMs(), cfg.maxConcurrentBatches());
    }

    /** Hot path: feed one undelivered-to-offline observation into the coalescing window. Non-blocking. */
    @Override
    public void push(OfflineEvent event) {
        if (!enabled || event == null || !event.hasRecipient()) {
            return; // disabled, or an event we cannot key/partition — shed silently
        }
        batcher.enqueue(event);
    }

    /** Graceful shutdown: flush the in-flight window and block until published (or timeout). No-op if disabled. */
    @Override
    public void drain(Duration timeout) {
        if (batcher != null) {
            batcher.drain(timeout);
        }
    }

    /** Sink for one coalesced batch (unique keys): publish each event to Kafka; a failed publish is soft. */
    private Uni<Void> sink(Collection<OfflineEvent> batch) {
        return Multi.createFrom().iterable(batch)
                .onItem().transformToUniAndMerge(event -> publisher.publish(event)
                        .onFailure().invoke(e -> {
                            publishErrors.accept(1);
                            log.warn("Offline-notify publish failed for recipient {} — dropped (message stays in outbox): {}",
                                    event.recipientId(), e.getMessage());
                        })
                        .onFailure().recoverWithNull())
                .collect().asList()
                .replaceWithVoid();
    }
}
