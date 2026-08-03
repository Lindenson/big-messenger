package org.hormigas.ws.core.router.stage.stages;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import org.hormigas.ws.core.router.context.RouterContext;
import org.hormigas.ws.core.router.stage.PipelineStage;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.ports.message.ReadReceipts;
import org.hormigas.hl.contracts.stage.StageResult;
import org.hormigas.hl.contracts.tetris.TetrisMarker;


@ApplicationScoped
@RequiredArgsConstructor
public class TetrisAckStage implements PipelineStage<RouterContext<Message>> {

    @Inject
    TetrisMarker tetrisMarker;

    @Inject
    ReadReceipts receipts;

    @Override
    public Uni<RouterContext<Message>> apply(RouterContext<Message> ctx) {
        // The recipient's delivery ACK both advances the GC watermark (onAck) and confirms receipt,
        // so the persisted status moves SENT→DELIVERED (UC-U13). correlationId carries the delivered
        // message's id; the status write is best-effort and must never fail the ACK pipeline.
        String deliveredMessageId = ctx.getPayload().getCorrelationId();
        return tetrisMarker.onAck(ctx.getPayload().getSenderId(), ctx.getPayload().getAckId())
                .onItem().invoke(r -> ctx.setAcknowledged(r.isSuccess() ? StageResult.passed() : StageResult.failed()))
                .call(() -> deliveredMessageId == null
                        ? Uni.createFrom().voidItem()
                        : receipts.markDelivered(deliveredMessageId).onFailure().recoverWithItem(0))
                .replaceWith(ctx)
                .onFailure().invoke(ctx::setError)
                .onFailure().recoverWithItem(ctx);
    }
}