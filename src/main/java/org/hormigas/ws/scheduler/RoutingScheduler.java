package org.hormigas.ws.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.hl.contracts.poller.BatchPoller;

@Slf4j
@ApplicationScoped
@IfBuildProfile("prod")
public class RoutingScheduler {

    @Inject
    BatchPoller batchPoller;

    @Inject
    MeterRegistry registry;

    private Counter errorCounter;

    @PostConstruct
    void init() {
        errorCounter = Counter.builder("messenger.poller.errors")
                .description("Errors during an outbox poll cycle")
                .register(registry);
    }

    @Scheduled(every = "${processing.messages.outbound.polling-ms}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollOutbox() {
        // The scheduler drives the reactive pipeline: subscribe here rather than in a delegating wrapper.
        batchPoller.poll().subscribe().with(
                nothing -> {},
                failure -> {
                    log.error("Error in scheduled outbox polling", failure);
                    errorCounter.increment();
                });
    }
}
