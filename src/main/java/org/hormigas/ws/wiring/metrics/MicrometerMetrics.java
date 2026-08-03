package org.hormigas.ws.wiring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hormigas.hl.batch.metrics.BatchMetrics;
import org.hormigas.hl.batch.metrics.MicrometerBatchMetrics;
import org.hormigas.ws.ports.metrics.Metrics;

import java.util.function.DoubleConsumer;

/**
 * Micrometer-backed {@link Metrics} — the edge that holds the {@link MeterRegistry}, so core use-cases speak
 * the {@code Metrics} seam and stay free of {@code io.micrometer}. Same {@code @Produces}-at-the-edge spirit
 * as {@code wiring.highload.HighloadProducers}.
 */
@ApplicationScoped
public class MicrometerMetrics implements Metrics {

    @Inject
    MeterRegistry registry;

    @Override
    public BatchMetrics batch(String prefix) {
        return new MicrometerBatchMetrics(registry, prefix);
    }

    @Override
    public DoubleConsumer counter(String name, String description) {
        Counter c = Counter.builder(name).description(description).register(registry);
        return c::increment;
    }
}
