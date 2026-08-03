package org.hormigas.ws.core.router.pipeline;

import io.smallrye.context.api.CurrentThreadContext;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.context.ThreadContext;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.hormigas.ws.core.router.InboundRouter;
import org.hormigas.ws.core.router.context.InboundPrototype;
import org.hormigas.ws.core.router.context.RouterContext;
import org.hormigas.ws.core.router.logger.RouterLogger;
import org.hormigas.ws.core.router.logger.inout.InboundRouterLogger;
import org.hormigas.ws.core.router.stage.stages.*;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageEnvelope;
import org.hormigas.ws.domain.message.MessageType;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class MessageInboundRouter implements InboundRouter<Message> {

    private final org.hormigas.ws.core.router.PipelineResolver<Message, MessageType> pipelineResolver;
    private final AuthorizationStage authorizationStage;
    private final OutboxStage outboxStage;
    private final DeliveryStage deliveryStage;
    private final AckStage ackStage;
    private final CleanCacheStage cleanCacheStage;
    private final CacheStage cacheStage;
    private final FinalStage finalStage;
    private final InboundPrototype prototype;
    private final TetrisAckStage tetrisAckStage;
    private final ReadStage readStage;
    private final SystemAckStage systemAckStage;
    private final AsyncHandoffStage asyncHandoffStage;


    private final RouterLogger<Message> logger = new InboundRouterLogger();

    // Hot path: the inbound pipeline uses only @ApplicationScoped stages + reactive clients and sets/clears
    // MDC synchronously in the WS handler (not across the async chain), so it needs NO propagated thread
    // context. Disabling it drops the per-message SmallRye context capture/restore that a saturated JFR
    // showed at ~30% of CPU (SlowCapturedContextState) — pure ingest-throughput headroom.
    @CurrentThreadContext(propagated = {}, unchanged = ThreadContext.ALL_REMAINING)
    @Override
    public Uni<MessageEnvelope<Message>> routeIn(Message message) {

        var pipeline = pipelineResolver.resolvePipeline(message);
        logger.logRoutingStart(message, pipeline);
        var context = prototype.createOutboundContext(pipeline, message);

        Uni<RouterContext<Message>> processed = switch (pipeline) {
            // Persistent chat (CHAT_IN): authorize (member + not blocked, stamp recipient) FIRST;
            // a rejected send short-circuits to FinalStage (no persist/deliver). After persist + sender ack,
            // AsyncHandoffStage switches recipient delivery onto the fast-delivery publisher's own threads so
            // it no longer gates inbound persist throughput; the outbox poller is the catch-up delivery path.
            case INBOUND_PERSISTENT -> authorizationStage.apply(context)
                    .onItem().transformToUni(ctx -> ctx.hasError()
                            ? finalStage.apply(ctx)
                            : outboxStage.apply(ctx)
                                    .onItem().transformToUni(ackStage::apply)
                                    .onItem().transformToUni(asyncHandoffStage::apply)
                                    .onItem().transformToUni(finalStage::apply));

            // Signaling (SIGNAL_IN): same send-guard, then live (cached) delivery.
            case INBOUND_CACHED -> authorizationStage.apply(context)
                    .onItem().transformToUni(ctx -> ctx.hasError()
                            ? finalStage.apply(ctx)
                            : deliveryStage.apply(ctx)
                                    .onItem().transformToUni(cacheStage::apply)
                                    .onItem().transformToUni(finalStage::apply));

            case INBOUND_DIRECT -> deliveryStage.apply(context)
                    .onItem().transformToUni(finalStage::apply);

            case ACK_PERSISTENT -> tetrisAckStage.apply(context)
                    .onItem().transformToUni(cleanCacheStage::apply)
                    .onItem().transformToUni(finalStage::apply);

            case ACK_CACHED -> cleanCacheStage.apply(context)
                    .onItem().transformToUni(finalStage::apply);

            // Read receipt (READ_IN): mark read + push READ_OUT to the peer.
            case READ -> readStage.apply(context)
                    .onItem().transformToUni(finalStage::apply);

            // System-notice confirmation (SYSTEM_ACK): ownership-checked retract of the dead-letter draft.
            case ACK_SYSTEM -> systemAckStage.apply(context)
                    .onItem().transformToUni(finalStage::apply);

            case SKIP -> finalStage.apply(context);

            default -> {
                log.error("Unhandled pipeline: {} for message: {}", pipeline, message);
                yield finalStage.apply(context.withError(new IllegalStateException("Unhandled pipeline: " + pipeline)));
            }
        };
        return logAndEnvelope(processed);
    }

    private Uni<MessageEnvelope<Message>> logAndEnvelope(Uni<RouterContext<Message>> processed) {
        return processed.onItem()
                .invoke(logger::logRoutingResult).onItem()
                .transform(pc -> MessageEnvelope.<Message>builder()
                        .message(pc.getPayload())
                        .processed(pc.isDone())
                        .build());
    }
}
