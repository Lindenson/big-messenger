package org.hormigas.ws.infrastructure.messaging.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.ws.domain.push.OfflineEvent;
import org.hormigas.ws.ports.push.OfflineEventPublisher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka adapter for {@link OfflineEventPublisher} (ADR-007 inter-service integration, producer side): emits
 * one JSON record per coalesced {@link OfflineEvent} to topic {@code chat.offline.notify} for the Go
 * notification service to consume. The Kafka <b>key is {@code recipientId}</b> — one partition per recipient
 * gives ordering + dedup locality on the consumer.
 *
 * <p>Value is UTF-8 JSON, serialized with the app's shared Jackson {@link ObjectMapper}. Only known fields
 * are written: unknown/null values are omitted (soft-degrade) by building the record from a map, so the wire
 * shape matches the contract exactly ({@code recipientId} always present, the rest best-effort). The topic
 * comes from {@link MessengerConfig.OfflinePush#topic()} and is set per-record so the mapped config is the
 * single source of truth.
 *
 * <p>Fire-and-forget: the send is not awaited on the delivery path (the coalescing facade drives it on its
 * own stream). {@code offline-push-out} is wired with {@code health-readiness-enabled=false} so an absent
 * broker (e2e/Karate, or prod before the topic is provisioned) never flips readiness DOWN — mirrors the
 * {@code order-events-in} consumer.
 */
@Slf4j
@ApplicationScoped
public class KafkaOfflineEventPublisher implements OfflineEventPublisher {

    @Inject
    @Channel("offline-push-out")
    MutinyEmitter<String> emitter;

    @Inject
    ObjectMapper mapper;

    @Inject
    MessengerConfig config;

    @Override
    public Uni<Void> publish(OfflineEvent event) {
        final String json;
        try {
            json = mapper.writeValueAsString(toWire(event));
        } catch (Exception e) {
            // Serialization can only fail on a programming error; don't propagate onto the stream.
            log.warn("Failed to serialize offline-notify event for recipient {}: {}", event.recipientId(), e.getMessage());
            return Uni.createFrom().voidItem();
        }

        var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(event.recipientId())          // partition per recipient → ordering + dedup locality
                .withTopic(config.offlinePush().topic())
                .build();

        return emitter.sendMessage(Message.of(json).addMetadata(metadata));
    }

    /** Build the wire JSON as an ordered map, omitting unknown (null) fields — {@code recipientId} always present. */
    private Map<String, Object> toWire(OfflineEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipientId", e.recipientId()); // REQUIRED
        putIfPresent(m, "eventId", e.eventId());
        putIfPresent(m, "senderId", e.senderId());
        putIfPresent(m, "senderName", e.senderName());
        putIfPresent(m, "conversationId", e.conversationId());
        putIfPresent(m, "messageId", e.messageId());
        putIfPresent(m, "preview", e.preview()); // "" (attachment) is kept; only null is omitted
        putIfPresent(m, "kind", e.kind());
        putIfPresent(m, "occurredAt", e.occurredAt());
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, String value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
