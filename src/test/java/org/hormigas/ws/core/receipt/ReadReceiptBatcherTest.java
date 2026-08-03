package org.hormigas.ws.core.receipt;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageEnvelope;
import org.hormigas.ws.domain.message.MessageType;
import org.hormigas.ws.core.router.OutboundRouter;
import org.hormigas.ws.ports.conversation.ConversationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the thin read-receipt facade over its {@link org.hormigas.hl.batch.coalescing.CoalescingBatcher}:
 * that {@link ReadReceiptBatcher#observe} feeds the coalescing window and, once the real linger fires,
 * the two decoupled sink effects run — an async persist ({@link ConversationManager#advanceReadReceipts}
 * with the coalesced MAX read id per dialog-side) and one coalesced {@code READ_OUT} to the peer.
 * Ports are {@code @InjectMock}ed; the batcher and its linger are the real wiring (config-driven).
 * The coalescing primitive itself is exhaustively covered by {@code CoalescingBatcherTest}.
 */
@QuarkusTest
@DisplayName("ReadReceiptBatcher — observe → coalesce-to-MAX persist + coalesced READ_OUT (real linger)")
class ReadReceiptBatcherTest {

    @InjectMock
    ConversationManager conversations;

    @InjectMock
    OutboundRouter<Message> outboundRouter;

    @Inject
    ReadReceiptBatcher buffer;

    @BeforeEach
    void setUp() {
        when(conversations.advanceReadReceipts(any())).thenReturn(Uni.createFrom().voidItem());
        when(outboundRouter.routeOut(any())).thenReturn(Uni.createFrom().item(MessageEnvelope.<Message>builder().build()));
    }

    @Test
    @DisplayName("observe coalesces to the MAX readId per (conversation, reader) and dispatches one READ_OUT")
    void coalescesToMaxAndDispatchesReadOut() {
        buffer.observe(new ReadReceiptMark("c1", "u", "peer", "01A"));
        buffer.observe(new ReadReceiptMark("c1", "u", "peer", "01C"));
        buffer.observe(new ReadReceiptMark("c1", "u", "peer", "01B")); // out-of-order, below the max

        List<ReadReceiptMark> persisted = capturePersistedMarks();
        assertEquals(1, persisted.size(), "the three observations collapse to one dialog-side");
        assertEquals("01C", persisted.get(0).readId(), "the coalesced readId is the MAX");

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(outboundRouter, timeout(15_000)).routeOut(sent.capture());
        Message readOut = sent.getValue();
        assertEquals(MessageType.READ_OUT, readOut.getType());
        assertEquals("peer", readOut.getRecipientId(), "READ_OUT goes to the peer");
        assertEquals("01C", readOut.getCorrelationId(), "cumulative read-up-to = the MAX readId");
    }

    @Test
    @DisplayName("two distinct dialog-sides persist as one coalesced batch of 2")
    void distinctDialogSidesBatched() {
        buffer.observe(new ReadReceiptMark("c1", "u1", "peer1", "01A"));
        buffer.observe(new ReadReceiptMark("c2", "u2", "peer2", "01B"));

        assertEquals(2, capturePersistedMarks().size(), "two distinct dialog-sides → batch of 2");
    }

    // NB: cache eviction on a receipt write is now write-through inside CachingConversationManager
    // (ADR-020) — covered by CachingConversationManagerTest, no longer this facade's concern.

    @SuppressWarnings("unchecked")
    private List<ReadReceiptMark> capturePersistedMarks() {
        ArgumentCaptor<List<ReadReceiptMark>> captor = ArgumentCaptor.forClass(List.class);
        // timeout(): drive the real linger — poll until the async batch flush lands.
        verify(conversations, timeout(15_000)).advanceReadReceipts(captor.capture());
        return captor.getValue();
    }
}
