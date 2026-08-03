package org.hormigas.ws.infrastructure.websocket.inbound;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.hl.backpressure.AbstractBackpressurePublisher;
import org.hormigas.hl.backpressure.metrics.inout.IncomingPublisherMetrics;
import org.hormigas.ws.core.router.InboundRouter;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageEnvelope;
import org.hormigas.ws.ports.emit.ChatMessageEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.hormigas.hl.backpressure.BackpressureBuilder.PublisherKind.INCOMING;

/**
 * Inbound ingest publisher: WS frames are published here and routed through {@link InboundRouter#routeIn} on
 * a bounded-parallel subscription. Backpressure mechanics live in {@link AbstractBackpressurePublisher}; this
 * class adds only the shutdown gate ({@link #close}) and the {@link ChatMessageEmitter} entry for
 * server-originated messages.
 */
@ApplicationScoped
public class InboundPublisher
        extends AbstractBackpressurePublisher<Message, MessageEnvelope<Message>, IncomingPublisherMetrics>
        implements ChatMessageEmitter {

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    InboundRouter<Message> pipelineRouter;

    @Inject
    MessengerConfig config;

    private final AtomicBoolean closing = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        start(INCOMING, new IncomingPublisherMetrics(meterRegistry),
                pipelineRouter::routeIn, config.inbound().deliveryConcurrency(),
                config.streamRetry().minBackoffMs(), config.streamRetry().maxBackoffMs());
    }

    /** Close the front door on shutdown: reject new inbound so the downstream batchers can drain. */
    public void close() {
        closing.set(true);
    }

    /** Rejects new inbound while shutting down (the drop is silent — it is a controlled shutdown, not overload). */
    @Override
    protected boolean accepting() {
        return !closing.get();
    }

    /** {@link ChatMessageEmitter}: route a server-originated message through the same inbound pipeline. */
    @Override
    public boolean emit(Message message) {
        return publish(message);
    }

    @Override
    protected int queueLimit() {
        return config.inbound().queueSize();
    }

    @Override
    protected String label() {
        return "Inbound";
    }
}
