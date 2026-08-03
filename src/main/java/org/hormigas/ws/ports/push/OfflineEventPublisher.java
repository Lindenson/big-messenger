package org.hormigas.ws.ports.push;

import io.smallrye.mutiny.Uni;
import org.hormigas.ws.domain.push.OfflineEvent;

/**
 * Driven port: publish one coalesced {@link OfflineEvent} to the outside world (the Kafka topic
 * {@code chat.offline.notify} in the live adapter). Kept as a port so the coalescing facade in core depends
 * on this abstraction, never on the Kafka/SmallRye adapter — Kafka lives behind the port (hexagonal).
 *
 * <p>The returned {@link Uni} completes when the record is handed to the broker producer. Callers treat a
 * failure as soft (log + drop): the offline-notify pipeline is best-effort and must never back-pressure or
 * fail live delivery.
 */
public interface OfflineEventPublisher {

    Uni<Void> publish(OfflineEvent event);
}
