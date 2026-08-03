package org.hormigas.ws.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.hl.contracts.outbox.OutboxManager;
import org.hormigas.hl.contracts.tetris.TetrisMarker;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-hot-path observability for the delivery watermark layer. Gauges are backed by {@link AtomicLong}
 * caches so a Prometheus scrape NEVER touches Redis or Postgres — it reads the last sampled value. A
 * single low-frequency tick (every {@code messenger.metrics.sample-every}, default 10s)
 * refreshes them, so the DB is not "дёргается лишний раз": one cheap {@code count(*)} on the GC-trimmed
 * outbox per tick, plus one read-only Redis round-trip (ZCARD + HVALS-sum in one Lua) and the already
 * available safe-delete computation.
 *
 * <p>Exposed (Prometheus names, dots → underscores):</p>
 * <ul>
 *   <li>{@code messenger_tetris_safe_delete_id} — the low watermark (global safe-delete id); rows below
 *       it are GC-eligible. Flatlining while the outbox grows = the watermark is stuck.</li>
 *   <li>{@code messenger_tetris_minids_size} — clients currently pinning the watermark (ZCARD minids).</li>
 *   <li>{@code messenger_tetris_pending_total} — total un-ACKed ids held in the Redis Tetris layer.</li>
 *   <li>{@code messenger_outbox_rows} — durable outbox depth (SELECT count(*)).</li>
 * </ul>
 *
 * <p>The diagnostic that catches the ghost/stuck-watermark anomaly is the <b>divergence</b>: when
 * {@code pending_total}/{@code minids_size} stays high while {@code outbox_rows} is ~0, Redis is holding
 * ghost entries with no backing outbox row — exactly what the startup wipe+rehydrate fix eliminates.</p>
 */
@Slf4j
@ApplicationScoped
@IfBuildProfile("prod")
public class TetrisMetricsScheduler {

    @Inject
    TetrisMarker tetrisMarker;

    @Inject
    OutboxManager<Message> outboxManager;

    @Inject
    MeterRegistry registry;

    private final AtomicLong safeDeleteId = new AtomicLong(0);
    private final AtomicLong minidsSize = new AtomicLong(0);
    private final AtomicLong pendingTotal = new AtomicLong(0);
    private final AtomicLong outboxRows = new AtomicLong(0);

    @PostConstruct
    void registerGauges() {
        Gauge.builder("messenger.tetris.safe.delete.id", safeDeleteId, AtomicLong::get)
                .description("Global safe-delete id (low watermark); outbox rows below it are GC-eligible")
                .register(registry);
        Gauge.builder("messenger.tetris.minids.size", minidsSize, AtomicLong::get)
                .description("Clients currently pinning the GC watermark (ZCARD tetris:minids)")
                .register(registry);
        Gauge.builder("messenger.tetris.pending.total", pendingTotal, AtomicLong::get)
                .description("Total un-ACKed message ids held in the Redis Tetris layer")
                .register(registry);
        Gauge.builder("messenger.outbox.rows", outboxRows, AtomicLong::get)
                .description("Durable outbox row count (not-yet-collected messages)")
                .register(registry);
    }

    // Independent of the GC tick so a slow/failed GC never starves the metrics. SKIP overlap so a slow
    // sample can never pile up. All reads are non-blocking; we only write the AtomicLong caches.
    @Scheduled(every = "${messenger.metrics.sample-every:10s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sample() {
        Uni.combine().all().unis(
                        tetrisMarker.computeGlobalSafeDeleteId(),
                        tetrisMarker.sampleSizes(),
                        outboxManager.pendingCount())
                .asTuple()
                .subscribe().with(t -> {
                    safeDeleteId.set(t.getItem1() != null ? t.getItem1() : 0L);
                    long[] sizes = t.getItem2();
                    if (sizes != null && sizes.length == 2) {
                        minidsSize.set(sizes[0]);
                        pendingTotal.set(sizes[1]);
                    }
                    outboxRows.set(t.getItem3() != null ? t.getItem3() : -1L);
                    log.debug("Tetris metrics sampled: safeDelete={} minids={} pending={} outbox={}",
                            safeDeleteId.get(), minidsSize.get(), pendingTotal.get(), outboxRows.get());
                }, err -> log.warn("Tetris metrics sample failed: {}", err.getMessage()));
    }
}
