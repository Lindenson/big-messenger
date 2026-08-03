package org.hormigas.ws.scheduler;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.hl.contracts.garbage.GarbageCollector;
import org.hormigas.ws.scheduler.metrics.GcMetrics;

@Slf4j
@ApplicationScoped
@IfBuildProfile("prod")
public class GarbageScheduler {

    @Inject
    GarbageCollector collector;

    @Inject
    GcMetrics metrics;

    @Scheduled(every = "${processing.messages.collector.every-s}",
    concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void run() {
        // Drive the GC pipeline off the scheduler thread; record the pass (count + rows + duration) in the
        // success arm (the collector recovers its own failures to 0, so the error arm rarely fires).
        long start = System.nanoTime();
        collector.collect().runSubscriptionOn(Infrastructure.getDefaultExecutor()).subscribe().with(
                collected -> {
                    metrics.recordRun(collected != null ? collected : 0, System.nanoTime() - start);
                    log.debug("Garbage collection finished, total collected: {}", collected);
                },
                err -> {
                    metrics.recordError();
                    log.error("Garbage collection failed", err);
                });
    }
}
