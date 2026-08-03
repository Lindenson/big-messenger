package org.hormigas.ws.core.receipt;

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
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.core.router.OutboundRouter;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageEnvelope;
import org.hormigas.ws.domain.message.MessageType;
import org.hormigas.ws.ports.channel.DeliveryChannel;
import org.hormigas.ws.ports.conversation.ConversationManager;
import org.hormigas.ws.ports.metrics.Metrics;

import java.time.Duration;
import java.util.Collection;
import java.util.function.DoubleConsumer;
import java.util.List;

/**
 * Write-behind read-receipt buffer (UC-U14) — a THIN facade that wires a {@link CoalescingBatcher} (the
 * reactive coalescing primitive) to its ports. It owns no flow-control of its own: coalescing, linger and
 * decoupling all live in the batcher's stream (mirrors how {@code ReadStatusBatcher} wired
 * {@code GroupCommitBatcher}). Ownership: this is a pipeline/streaming element, fed by {@code ReadStage}
 * (WS) and the REST adapter; the conversation use-case only resolves the authorized {@link ReadReceiptMark}.
 *
 * <p>Per coalesced batch (unique {@code (conversation, reader)} with the MAX read id) the sink runs, driven
 * by the batcher stream (no hand-rolled subscribe), two fire-and-forget effects: an async DB write
 * ({@link ConversationManager#advanceReadReceipts}, one {@code UPDATE … GREATEST}) and one coalesced
 * {@code READ_OUT} per key to the peer via {@link DeliveryChannel}. The receipt is soft: a failed sink is
 * logged and dropped, self-healing on the next READ_IN (GREATEST). The durable truth is the conversation
 * receipt column — the batcher's window is only the transient coalescing buffer.
 */
@Slf4j
@Startup
@ApplicationScoped
public class ReadReceiptBatcher implements ReadReceiptSink {

    private record Key(String conversationId, String readerId) {}

    @Inject
    ConversationManager conversations;

    @Inject
    OutboundRouter<Message> outboundRouter;

    @Inject
    MessengerConfig config;

    @Inject
    Metrics metrics;

    private CoalescingBatcher<Key, ReadReceiptMark> batcher;
    private DoubleConsumer persistErrors;

    @PostConstruct
    void init() {
        persistErrors = metrics.counter("messenger.read.receipt.persist.errors",
                "Async receipt-persist failures (self-heal on next READ_IN)");
        var cfg = config.readBatch();
        batcher = CoalescingBuilder.<Key, ReadReceiptMark>create()
                .withKey(m -> new Key(m.conversationId(), m.readerId()))
                .withMerge((a, b) -> b.readId().compareTo(a.readId()) > 0 ? b : a) // keep the greater (later) read id
                .withSink(this::sink)
                .withMaxSize(cfg.maxSize()).withLingerMs(cfg.lingerMs()).withConcurrency(cfg.maxConcurrentBatches())
                .withRetryBackoff(config.streamRetry().minBackoffMs(), config.streamRetry().maxBackoffMs())
                .withMetrics(metrics.batch("messenger.read.receipt"))
                .build();
        log.info("Read-receipt coalescing buffer ready (maxSize={}, lingerMs={}, maxConcurrentBatches={})",
                cfg.maxSize(), cfg.lingerMs(), cfg.maxConcurrentBatches());
    }

    /** Hot path: feed one authorized read observation into the coalescing window. Non-blocking, fire-and-forget. */
    public void observe(ReadReceiptMark mark) {
        batcher.enqueue(mark);
    }

    /** Graceful shutdown: flush the in-flight window and block until sinked (or timeout). */
    public void drain(Duration timeout) {
        batcher.drain(timeout);
    }

    /** Sink for one coalesced batch: async persist + coalesced READ_OUT dispatch, both driven by the stream. */
    private Uni<Void> sink(Collection<ReadReceiptMark> batch) {
        List<ReadReceiptMark> marks = List.copyOf(batch);

        // (1) durable write — one UPDATE ... GREATEST. Soft: log+swallow so one bad batch never stalls the
        //     stream; the receipt self-heals on the next READ_IN. Cache eviction is write-through inside the
        //     caching ConversationManager (ADR-020) — advanceReadReceipts evicts the touched rows so a
        //     subsequent history/list read serves the fresh peerLastReadId; not a concern of this stage.
        Uni<Void> persist = conversations.advanceReadReceipts(marks)
                .onFailure().invoke(e -> {
                    persistErrors.accept(1);
                    log.error("Receipt persist failed ({} keys) — self-heals on next READ_IN", marks.size(), e);
                })
                .onFailure().recoverWithNull()
                .replaceWithVoid();

        // (2) one coalesced READ_OUT ("read up to X") per key to the peer, dispatched through the shared
        //     OUTBOUND pipeline (OutboundRouter.routeOut → OUTBOUND_DIRECT), the same entry the outbox poller
        //     uses — so delivery goes through the router's stages, never a raw channel. Best-effort, isolated.
        Uni<Void> dispatch = Multi.createFrom().iterable(marks)
                .onItem().transformToUniAndMerge(m -> outboundRouter.routeOut(readOut(m))
                        .onFailure().recoverWithItem((MessageEnvelope<Message>) null))
                .collect().asList()
                .replaceWithVoid();

        return Uni.combine().all().unis(persist, dispatch).discardItems();
    }

    /**
     * READ_OUT to the peer: cumulative "read up to X" (X = correlationId = the read id). The messageId
     * includes the readId so each cumulative update is a DISTINCT frame — the router's delivery-stage
     * idempotency (isInProgress by messageId) would otherwise dedup a second read under a stable id.
     */
    private Message readOut(ReadReceiptMark m) {
        return Message.builder()
                .type(MessageType.READ_OUT)
                .conversationId(m.conversationId())
                .senderId(m.readerId())
                .recipientId(m.peerId())
                .correlationId(m.readId())
                .messageId("read-" + m.conversationId() + "-" + m.readerId() + "-" + m.readId())
                .serverTimestamp(System.currentTimeMillis())
                .build();
    }
}
