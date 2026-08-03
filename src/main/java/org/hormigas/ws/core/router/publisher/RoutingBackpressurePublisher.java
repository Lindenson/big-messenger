package org.hormigas.ws.core.router.publisher;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.hl.backpressure.AbstractBackpressurePublisher;
import org.hormigas.hl.backpressure.metrics.inout.OutgoingPublisherMetrics;
import org.hormigas.ws.core.router.OutboundRouter;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageEnvelope;

import static org.hormigas.hl.backpressure.BackpressureBuilder.PublisherKind.OUTGOING;

/**
 * Outbound (poller) delivery publisher: the {@link org.hormigas.ws.scheduler.RoutingScheduler} poller feeds
 * durable outbox rows here and they are delivered via {@link OutboundRouter#routeOut}. All the backpressure
 * mechanics live in {@link AbstractBackpressurePublisher}; this class only wires the sink + config.
 * Bounded-parallel delivery does NOT preserve per-conversation order (keyed sharding is a planned follow-up).
 */
@ApplicationScoped
public class RoutingBackpressurePublisher
        extends AbstractBackpressurePublisher<Message, MessageEnvelope<Message>, OutgoingPublisherMetrics> {

    @Inject
    OutgoingPublisherMetrics metrics;

    @Inject
    OutboundRouter<Message> pipelineRouter;

    @Inject
    MessengerConfig config;

    @PostConstruct
    void init() {
        start(OUTGOING, metrics,
                pipelineRouter::routeOut, config.outbound().deliveryConcurrency(),
                config.streamRetry().minBackoffMs(), config.streamRetry().maxBackoffMs());
    }

    @Override
    protected int queueLimit() {
        return config.outbound().queueSize();
    }

    @Override
    protected String label() {
        return "Outbound";
    }
}
