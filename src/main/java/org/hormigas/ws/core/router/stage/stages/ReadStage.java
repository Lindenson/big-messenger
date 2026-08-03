package org.hormigas.ws.core.router.stage.stages;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.core.conversation.Chats;
import org.hormigas.ws.core.receipt.ReadReceiptBatcher;
import org.hormigas.ws.core.router.context.RouterContext;
import org.hormigas.ws.core.router.stage.PipelineStage;
import org.hormigas.ws.domain.message.Message;

/**
 * READ_IN pipeline (UC-U14) — a thin adapter. The reader (authenticated sender) reports it read the
 * conversation up to the client-supplied {@code lastReadId} (carried in {@code correlationId}). The
 * membership check, peer resolution and lastReadId validation live in the conversation use-case
 * ({@link Chats#resolveReadMark}); this stage only feeds the resolved observation into the pipeline's
 * coalescing {@link ReadReceiptBatcher}. It never touches the DB or dispatches READ_OUT — the buffer's
 * stream does both, coalesced and off this thread. Soft receipt → no gating: hand off and return.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class ReadStage implements PipelineStage<RouterContext<Message>> {

    private final Chats chats;
    private final ReadReceiptBatcher buffer;

    @Override
    public Uni<RouterContext<Message>> apply(RouterContext<Message> ctx) {
        Message msg = ctx.getPayload();
        return chats.resolveReadMark(msg.getConversationId(), msg.getSenderId(), msg.getCorrelationId())
                .onItem().invoke(g -> {
                    if (g.value() != null) {
                        buffer.observe(g.value()); // OK + valid mark → coalesce (a null value = nothing to record)
                    }
                })
                .replaceWith(ctx)
                .onFailure().recoverWithItem(err -> {
                    log.error("READ_IN handling failed conv={}: {}", msg.getConversationId(), err.getMessage());
                    return ctx;
                });
    }
}
