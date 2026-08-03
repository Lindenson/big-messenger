package org.hormigas.ws.core.router.stage.stages;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import org.hormigas.ws.core.router.context.RouterContext;
import org.hormigas.ws.core.router.stage.PipelineStage;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.hl.contracts.tetris.TetrisMarker;


@ApplicationScoped
@RequiredArgsConstructor
public class TetrisSentStage implements PipelineStage<RouterContext<Message>> {

    @Inject
    TetrisMarker tetrisMarker;

    @Override
    public Uni<RouterContext<Message>> apply(RouterContext<Message> ctx) {
        // Tetris is the engine's GC authority tied to WS-connection status, so it must see EVERY processed
        // message — not only delivered ones. A successful delivery pins the id (pending until ACK); a skipped
        // one (recipient offline) must NOT pin but MUST advance the lastid fallback, otherwise a stream of
        // offline-delivered messages freezes safe-delete and the outbox grows forever without draining.
        if (!ctx.getDelivered().isSuccess()) {
            return tetrisMarker.onSkipped(ctx.getPayload().getId()).replaceWith(ctx)
                    .onFailure().recoverWithItem(ctx);
        }

        return tetrisMarker.onSent(ctx.getPayload().getRecipientId(), ctx.getPayload().getId()).replaceWith(ctx)
                        .onFailure().invoke(ctx::setError)
                        .onFailure().recoverWithItem(ctx);
    }
}
