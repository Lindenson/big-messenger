package org.hormigas.ws.wiring.highload;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.ws.config.RateLimitConfig;
import org.hormigas.ws.core.router.publisher.RoutingBackpressurePublisher;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.hl.contracts.feedback.Regulator;
import org.hormigas.hl.contracts.garbage.GarbageCollector;
import org.hormigas.hl.contracts.outbox.OutboxManager;
import org.hormigas.hl.contracts.poller.BatchPoller;
import org.hormigas.hl.contracts.ratelimit.RateLimiter;
import org.hormigas.hl.contracts.tetris.TetrisMarker;
import org.hormigas.hl.contracts.watermark.Watermark;
import org.hormigas.hl.garbage.OutboxGarbageCollector;
import org.hormigas.hl.poller.OutboxPoller;
import org.hormigas.hl.poller.metrics.MicrometerPollerMetrics;
import org.hormigas.hl.ratelimit.RateLimit;
import org.hormigas.hl.ratelimit.TokenBucketRateLimiter;
import org.hormigas.hl.watermark.TetrisWatermark;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * CDI wiring for the high-performance mechanisms: each producer builds the framework-neutral
 * {@code messenger-hl} POJO from app config/beans and places it in the context. Consumers just
 * {@code @Inject} the contract — no delegating {@code Async…} wrappers.
 */
@ApplicationScoped
public class HighloadProducers {

    /** The rate-limit gate: the pure token bucket, configured from {@link RateLimitConfig}. */
    @Produces
    @ApplicationScoped
    RateLimiter rateLimiter(RateLimitConfig config) {
        Map<String, RateLimit> groups = config.groups().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toRateLimit(e.getValue())));
        return TokenBucketRateLimiter.builder()
                .enabled(config.enabled())
                .defaultLimit(toRateLimit(config.defaultLimit()))
                .groups(groups)
                .bucketCacheMax(config.bucketCacheMax())
                .bucketIdleMinutes(config.bucketIdleMinutes())
                .build();
    }

    private static RateLimit toRateLimit(RateLimitConfig.Limit limit) {
        return new RateLimit(limit.permitsPerSecond(), limit.burst());
    }

    /** Safe-delete watermark over the Tetris marker port. Fire-and-forget subscription is the caller's. */
    @Produces
    @ApplicationScoped
    Watermark watermark(TetrisMarker tetrisMarker) {
        return new TetrisWatermark(tetrisMarker);
    }

    /** Outbox garbage collector over the durable outbox + Tetris marker. Subscription is the caller's. */
    @Produces
    @ApplicationScoped
    GarbageCollector garbageCollector(OutboxManager<Message> outboxManager, TetrisMarker tetrisMarker) {
        return new OutboxGarbageCollector<>(outboxManager, tetrisMarker);
    }

    /** The batched outbox delivery poller, wired to the routing (backpressure) publisher + adaptive regulator. */
    @Produces
    @ApplicationScoped
    BatchPoller batchPoller(RoutingBackpressurePublisher publisher,
                            OutboxManager<Message> outboxManager,
                            Regulator regulator,
                            MeterRegistry registry,
                            MessengerConfig config) {
        return OutboxPoller.<Message>builder()
                .publisher(publisher)
                .outboxManager(outboxManager)
                .metrics(new MicrometerPollerMetrics(registry))
                .regulator(regulator)
                .batchSize(config.outbound().batchSize())
                .build();
    }
}
