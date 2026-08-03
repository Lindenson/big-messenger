package org.hormigas.ws.core.router.publisher;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.hl.backpressure.AbstractBackpressurePublisher;
import org.hormigas.hl.backpressure.metrics.inout.FastDeliveryPublisherMetrics;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageEnvelope;

import java.util.function.Function;

import static org.hormigas.hl.backpressure.BackpressureBuilder.PublisherKind.OUTGOING;

/**
 * Inbound live-delivery FAST-PATH publisher. Same bounded-parallel backpressure shell as the outbound poller
 * ({@link AbstractBackpressurePublisher}), but its sink is NOT hardcoded: the {@code HandoffSubPipeline}
 * supplies the delivery continuation once via {@link #configure} at startup, and this publisher runs it on
 * its OWN subscription/threads — decoupling recipient delivery from the inbound persist path. Anything shed
 * at the queue gate is picked up by the durable outbox poller.
 */
@Slf4j
@ApplicationScoped
public class FastDeliveryPublisher
        extends AbstractBackpressurePublisher<Message, MessageEnvelope<Message>, FastDeliveryPublisherMetrics> {

    @Inject
    FastDeliveryPublisherMetrics metrics;

    @Inject
    MessengerConfig config;

    /**
     * Wire the delivery continuation as this publisher's sink and start its subscription. Called once by the
     * router/hand-off sub-pipeline at startup — this publisher only provides the backpressure + thread
     * hand-off, never naming the downstream stages. Idempotent: a second call is ignored.
     */
    public void configure(Function<Message, Uni<MessageEnvelope<Message>>> continuation) {
        if (ready.get()) {
            log.warn("FastDeliveryPublisher already configured — ignoring");
            return;
        }
        start(OUTGOING, metrics,
                continuation, config.fastDelivery().deliveryConcurrency(),
                config.streamRetry().minBackoffMs(), config.streamRetry().maxBackoffMs());
    }

    @Override
    protected int queueLimit() {
        return config.fastDelivery().queueSize();
    }

    @Override
    protected String label() {
        return "Fast-path";
    }
}
