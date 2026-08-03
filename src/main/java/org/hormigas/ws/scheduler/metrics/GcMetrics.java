package org.hormigas.ws.scheduler.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics companion for the outbox garbage collector — the collector was a blind spot: its per-pass
 * deleted-row count (returned as {@code Uni<Integer>}) was logged at DEBUG and otherwise discarded, so
 * under load there was no way to tell whether GC was keeping up, starved of eligible rows (a pinned
 * safe-delete watermark), or silently failing. These meters make the delete path observable alongside the
 * existing {@code messenger.tetris.*} watermark gauges: {@code gc.deleted} rate vs {@code tetris.safe.delete.id}
 * advance vs {@code outbox.rows} tells watermark-pinning (deleted≈0 while eligible>0) apart from a slow
 * delete path (deleted saturates but outbox still grows). Registered at {@link Startup} so the dashboard has
 * them before the first GC pass.
 */
@Startup
@ApplicationScoped
public class GcMetrics {

    @Inject
    MeterRegistry registry;

    private Counter runs;
    private Counter deleted;
    private Counter errors;
    private Timer duration;
    private final AtomicLong lastDeleted = new AtomicLong(0);

    @PostConstruct
    void init() {
        runs = Counter.builder("messenger.gc.runs")
                .description("Outbox GC passes executed").register(registry);
        deleted = Counter.builder("messenger.gc.deleted")
                .description("Outbox rows deleted by GC (rate = actual cleanup throughput)").register(registry);
        errors = Counter.builder("messenger.gc.errors")
                .description("Outbox GC passes that failed").register(registry);
        duration = Timer.builder("messenger.gc.duration")
                .description("Outbox GC pass wall-clock duration").register(registry);
        Gauge.builder("messenger.gc.last.deleted", lastDeleted, AtomicLong::get)
                .description("Rows deleted in the most recent GC pass").register(registry);
    }

    /** Record a completed GC pass: how many rows it deleted and how long it took. */
    public void recordRun(int deletedRows, long nanos) {
        runs.increment();
        if (deletedRows > 0) {
            deleted.increment(deletedRows);
        }
        lastDeleted.set(deletedRows);
        duration.record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Record a GC pass that failed (the reactive chain errored). */
    public void recordError() {
        errors.increment();
    }
}
