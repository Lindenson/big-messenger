package org.hormigas.ws.wiring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.hormigas.hl.backpressure.metrics.inout.FastDeliveryPublisherMetrics;
import org.hormigas.hl.backpressure.metrics.inout.OutgoingPublisherMetrics;
import org.hormigas.hl.contracts.feedback.events.OutgoingHealthEvent;
import org.hormigas.hl.contracts.feedback.provider.OutEventProvider;

/**
 * CDI wiring for the delivery publishers' Micrometer metrics. The concrete metrics impls are built here at
 * the edge from {@link MeterRegistry}, so the core publishers depend only on the metrics seam type they are
 * generic over — never on {@code io.micrometer} directly. Same {@code @Produces}-at-the-edge pattern as
 * {@code wiring.highload.HighloadProducers}.
 */
@ApplicationScoped
public class DeliveryMetricsProducers {

    /** Inbound fast-path delivery metrics ({@code messenger.delivery.* path=fast}). */
    @Produces
    @ApplicationScoped
    FastDeliveryPublisherMetrics fastDeliveryPublisherMetrics(MeterRegistry registry) {
        return new FastDeliveryPublisherMetrics(registry);
    }

    /** Outbound (poller) delivery metrics ({@code messenger.delivery.* path=poller}); also fires the feedback health signal. */
    @Produces
    @ApplicationScoped
    OutgoingPublisherMetrics outgoingPublisherMetrics(MeterRegistry registry,
                                                      OutEventProvider<OutgoingHealthEvent> events) {
        return new OutgoingPublisherMetrics(registry, events);
    }
}
