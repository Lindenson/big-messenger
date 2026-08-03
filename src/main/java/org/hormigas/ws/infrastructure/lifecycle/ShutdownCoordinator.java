package org.hormigas.ws.infrastructure.lifecycle;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.config.ShutdownConfig;
import org.hormigas.ws.core.router.InboundPersistBatcher;
import org.hormigas.ws.core.push.OfflinePusher;
import org.hormigas.ws.core.receipt.ReadReceiptBatcher;
import org.hormigas.ws.infrastructure.websocket.inbound.InboundPublisher;

import java.time.Duration;

/**
 * Graceful shutdown orchestration (Phase 5a). On {@link ShutdownEvent}, in strict order:
 *
 * <ol>
 *   <li><b>Close the front door</b> — {@link InboundPublisher#close()} rejects new inbound so nothing new
 *       enters the pipeline while we drain.</li>
 *   <li><b>Drain the persist batcher</b> — flush the in-flight {@code history+outbox} buffer and block
 *       until those transactions commit (or the timeout elapses).</li>
 *   <li><b>Drain the read-status batcher</b> — same, for READ receipts.</li>
 *   <li><b>Drain the offline-notify batcher</b> — flush the in-flight coalescing window and publish the
 *       pending offline events before the broker producer is torn down.</li>
 * </ol>
 *
 * <p>Each drain is bounded by {@code processing.shutdown.drain-timeout-ms}. Anything still un-committed at
 * the deadline is simply not acked, so its sender redelivers on reconnect — the {@code ack-after-commit}
 * durability guarantee is never weakened. The outbox poller ({@code @Scheduled}) is stopped by Quarkus,
 * and the Redis client is closed by Quarkus after observers run, so in-flight presence/idempotency ops
 * complete before the pool goes away.
 */
@Slf4j
@ApplicationScoped
public class ShutdownCoordinator {

    @Inject
    InboundPublisher inboundPublisher;

    @Inject
    InboundPersistBatcher persistBatcher;

    @Inject
    ReadReceiptBatcher readReceiptBuffer;

    @Inject
    OfflinePusher offlinePush;

    @Inject
    ShutdownConfig config;

    void onShutdown(@Observes ShutdownEvent event) {
        Duration timeout = Duration.ofMillis(config.drainTimeoutMs());
        log.info("Graceful shutdown: closing inbound and draining batchers (timeout {}ms)", config.drainTimeoutMs());

        inboundPublisher.close();       // 1. stop accepting new inbound
        persistBatcher.drain(timeout);  // 2. flush + commit in-flight persists
        readReceiptBuffer.drain(timeout); // 3. flush the in-flight coalescing window + block until sinked
        offlinePush.drain(timeout);   // 4. flush in-flight offline-push window + block until published

        log.info("Graceful shutdown: batchers drained");
    }
}
