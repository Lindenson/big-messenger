package org.hormigas.ws.core.router;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.hl.batch.groupcommit.GroupCommitBatcher;
import org.hormigas.hl.batch.groupcommit.GroupCommitBuilder;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.ports.metrics.Metrics;
import org.hormigas.hl.contracts.stage.StageResult;
import org.hormigas.hl.contracts.outbox.OutboxManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Group-commit facade for the inbound persist (plan B). A thin wiring over a {@link GroupCommitBatcher}
 * whose port op writes {@code history+outbox} in one transaction ({@link OutboxManager#saveBatch}) and
 * isolates poison rows (a rolled-back batch is retried row-by-row via {@link OutboxManager#save}, so one
 * bad row fails alone). Called by the persist pipeline stage; each enqueued message resolves with its own
 * {@link StageResult} when its batch commits.
 */
@Slf4j
@ApplicationScoped
public class InboundPersistBatcher {

    @Inject
    OutboxManager<Message> outbox;

    @Inject
    MessengerConfig config;

    @Inject
    Metrics metrics;

    private DoubleConsumer fallbacks;
    private GroupCommitBatcher<Message, StageResult<Message>> batcher;

    @PostConstruct
    void init() {
        var cfg = config.inbound().persistBatch();
        fallbacks = metrics.counter("messenger.persist.batch.fallbacks",
                "Messages retried individually after a batch transaction failure");

        batcher = GroupCommitBuilder.<Message, StageResult<Message>>create()
                .withBatchOp(this::persist)
                .withShedValue(StageResult.failed())
                .withMaxSize(cfg.maxSize()).withLingerMs(cfg.lingerMs()).withConcurrency(cfg.maxConcurrentBatches())
                .withRetryBackoff(config.streamRetry().minBackoffMs(), config.streamRetry().maxBackoffMs())
                .withMetrics(metrics.batch("messenger.persist.batch"))
                .build();

        log.info("Inbound persist batcher ready (maxSize={}, lingerMs={}, maxConcurrentBatches={})",
                cfg.maxSize(), cfg.lingerMs(), cfg.maxConcurrentBatches());
    }

    /** Enqueue a message for group-commit; resolves with its {@link StageResult} once its batch commits. */
    public Uni<StageResult<Message>> enqueue(Message message) {
        return batcher.enqueue(message);
    }

    /** Graceful shutdown: flush in-flight persists and block until committed or {@code timeout} elapses. */
    public void drain(java.time.Duration timeout) {
        batcher.drain(timeout);
    }

    /** Persist a batch in one transaction, isolate poison rows, and return results in input order. */
    private Uni<List<StageResult<Message>>> persist(List<Message> msgs) {
        return outbox.saveBatch(msgs)
                .onItem().transformToUni(results -> resolveFailures(msgs, results))
                .onItem().transform(resolved -> msgs.stream()
                        .map(m -> resolved.getOrDefault(m.getMessageId(), StageResult.<Message>failed()))
                        .toList());
    }

    /**
     * Isolate poison rows: if the batch transaction rolled back (some/all results FAILED), retry the
     * failed messages one at a time via {@link OutboxManager#save}. The transaction is all-or-nothing, so
     * a rollback committed nothing — re-persisting individually cannot double-insert.
     */
    private Uni<Map<String, StageResult<Message>>> resolveFailures(
            List<Message> msgs, Map<String, StageResult<Message>> results) {

        List<Message> failed = msgs.stream()
                .filter(m -> {
                    StageResult<Message> r = results.get(m.getMessageId());
                    return r == null || r.isFailed();
                })
                .toList();

        if (failed.isEmpty()) {
            return Uni.createFrom().item(results);
        }

        fallbacks.accept(failed.size());
        log.warn("Batch persist had {} failed row(s) — retrying individually to isolate the culprit", failed.size());

        Map<String, StageResult<Message>> merged = new HashMap<>(results);
        return Multi.createFrom().iterable(failed)
                .onItem().transformToUniAndConcatenate(m ->
                        outbox.save(m).onItem().invoke(r -> merged.put(m.getMessageId(), r)))
                .collect().asList()
                .replaceWith(merged);
    }
}
