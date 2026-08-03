package org.hormigas.ws.domain.push;

import org.hormigas.ws.domain.message.Message;

import java.time.Instant;
import java.util.UUID;

/**
 * Framework-free payload for an "offline recipient" notification (UC-U??/ADR-017-adjacent): emitted when a
 * durable chat/system message could not be delivered live because the recipient has no open WS session. It
 * feeds a separate Go notification service over Kafka (topic {@code chat.offline.notify}); the messenger
 * only produces the event, never the push itself.
 *
 * <p>Contract: {@code recipientId} is the only REQUIRED field (also the Kafka partition key — one partition
 * per recipient gives ordering + dedup locality on the consumer). Everything else is best-effort and
 * <b>soft-degrades</b>: an unknown value is left {@code null} and the Kafka adapter omits it from the wire
 * JSON. {@code preview} is an empty string (not omitted) for an attachment message — there is no text to
 * preview but the consumer still learns "something arrived".
 *
 * <p>Pure domain: no Jackson/Kafka/CDI here. Serialization to the wire JSON lives in the messaging adapter.
 */
public record OfflineEvent(
        String recipientId,      // REQUIRED — kratos id + Kafka key
        String eventId,          // uuid, for dedup/trace
        String senderId,         // kratos id
        String senderName,       // display name — often unknown here (see from()), then null/omitted
        String conversationId,
        String messageId,        // server ULID of the message
        String preview,          // short text; "" for attachments; null if no payload
        String kind,             // payload kind (e.g. "text", "attachment")
        String occurredAt        // RFC3339 (ISO-8601 UTC)
) {

    /** Attachment payload kind (mirrors {@code core.attachment.Attachments.KIND_ATTACHMENT}). */
    private static final String KIND_ATTACHMENT = "attachment";

    /** Preview cap — a notification teaser, not the message body. */
    static final int PREVIEW_MAX = 140;

    /**
     * Map an outbound {@link Message} that could not be delivered live onto the offline-notify contract.
     * The caller has already filtered to real chat/system messages. {@code senderName} is NOT carried on the
     * wire {@link Message} (the messenger addresses users by kratos id; display names live in the Identity
     * Directory Service), so it is left {@code null} here and omitted — the Go service resolves the name.
     */
    public static OfflineEvent from(Message message) {
        Message.Payload payload = message.getPayload();
        String kind = payload == null ? null : payload.getKind();
        String preview = preview(payload, kind);
        return new OfflineEvent(
                message.getRecipientId(),
                UUID.randomUUID().toString(),
                message.getSenderId(),
                null,                                   // senderName: not on Message — resolved downstream
                message.getConversationId(),
                message.getMessageId(),
                preview,
                kind,
                occurredAt(message)
        );
    }

    private static String preview(Message.Payload payload, String kind) {
        if (payload == null) {
            return null;
        }
        if (KIND_ATTACHMENT.equals(kind)) {
            return ""; // no text to preview, but the recipient still gets notified
        }
        String body = payload.getBody();
        if (body == null) {
            return "";
        }
        return body.length() <= PREVIEW_MAX ? body : body.substring(0, PREVIEW_MAX);
    }

    private static String occurredAt(Message message) {
        long epochMs = message.getServerTimestamp() > 0 ? message.getServerTimestamp()
                : message.getSenderTimestamp() > 0 ? message.getSenderTimestamp()
                : System.currentTimeMillis();
        return Instant.ofEpochMilli(epochMs).toString(); // ISO-8601 with 'Z' — a valid RFC3339 instant
    }

    /** Guard: an event with no recipient cannot be keyed/partitioned and must never be enqueued. */
    public boolean hasRecipient() {
        return recipientId != null && !recipientId.isBlank();
    }
}
