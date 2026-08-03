package org.hormigas.ws.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hormigas.hl.batch.metrics.BatchMetrics;
import org.hormigas.hl.batch.metrics.MicrometerBatchMetrics;
import org.hormigas.ws.ports.metrics.Metrics;

import java.util.function.DoubleConsumer;

/**
 * Test {@link Metrics} backed by a real {@link SimpleMeterRegistry} — mirrors the production
 * {@code wiring.metrics.MicrometerMetrics}. The registry is public so tests can assert on the meters
 * (e.g. {@code testMetrics.registry.get("…").counter().count()}).
 */
public class TestMetrics implements Metrics {

    public final MeterRegistry registry;

    public TestMetrics() {
        this(new SimpleMeterRegistry());
    }

    public TestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

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
