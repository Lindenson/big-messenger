package org.hormigas.ws.core.router.pipeline;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.core.router.OutboundRouter;
import org.hormigas.ws.core.router.context.RouterContext;
import org.hormigas.ws.core.router.publisher.FastDeliveryPublisher;
import org.hormigas.ws.domain.message.Message;

/**
 * Hand-off sub-pipeline: the decoupling seam between the inbound pipeline and recipient delivery. It owns the
 * {@link FastDeliveryPublisher} and wires its sink to the shared delivery pipeline ({@link OutboundRouter#routeOut},
 * the same path the outbox poller feeds), so everything downstream of the hand-off runs on the publisher's OWN
 * subscription/threads — off the inbound persist path.
 *
 * <p>{@link org.hormigas.ws.core.router.stage.stages.AsyncHandoffStage} is a thin pipeline stage that just
 * delegates here; the router composes stages and never touches the publisher. Fire-and-forget: durability is
 * the outbox (persisted before the hand-off), so a shed hand-off is simply picked up by the catch-up poller.
 */
@Slf4j
@Startup // configure the publisher's subscription at boot, before any traffic
@ApplicationScoped
@RequiredArgsConstructor
public class HandoffSubPipeline {

    private final FastDeliveryPublisher fastDeliveryPublisher;
    private final OutboundRouter<Message> outboundRouter;

    @PostConstruct
    void wire() {
        // A CHAT_OUT resolves to OUTBOUND_CACHED (delivery→cache→tetris→final), so the fast path and the
        // catch-up poller share one delivery pipeline (no duplication) and both inherit its context-off.
        fastDeliveryPublisher.configure(outboundRouter::routeOut);
    }

    /**
     * Hand the persisted message off to the async delivery path (the decoupling), then return the context so
     * the inbound pipeline continues to its final stage. Only durably-persisted messages are handed off; a
     * shed hand-off (publisher queue full) is left for the poller.
     */
    public Uni<RouterContext<Message>> handoff(RouterContext<Message> ctx) {
        if (ctx.getPersisted().isSuccess() && !fastDeliveryPublisher.publish(ctx.getPayload())) {
            log.debug("Fast-path hand-off shed — poller will deliver {}", ctx.getPayload().getMessageId());
        }
        return Uni.createFrom().item(ctx);
    }
}
