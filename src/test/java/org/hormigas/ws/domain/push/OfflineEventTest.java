package org.hormigas.ws.domain.push;

import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@link OfflineEvent#from(Message)} maps an undeliverable outbound {@link Message} onto the
 * offline-notify wire contract: recipientId required, a generated eventId, kind carried, preview from the
 * body (empty for attachments), an RFC3339 occurredAt, and senderName left null (not on the wire Message).
 */
@DisplayName("OfflineEvent.from — Message → offline-notify contract mapping")
class OfflineEventTest {

    private static Message.MessageBuilder base() {
        return Message.builder()
                .type(MessageType.CHAT_OUT)
                .recipientId("kratos-recipient")
                .senderId("kratos-sender")
                .conversationId("01CONV")
                .messageId("01ULID")
                .serverTimestamp(1_770_000_000_000L); // fixed epoch ms
    }

    @Test
    @DisplayName("text message maps every known field; senderName omitted; eventId generated; occurredAt RFC3339")
    void mapsTextMessage() {
        Message msg = base()
                .payload(Message.Payload.builder().kind("text").body("привет").build())
                .build();

        OfflineEvent e = OfflineEvent.from(msg);

        assertEquals("kratos-recipient", e.recipientId(), "recipientId is required and carried");
        assertTrue(e.hasRecipient());
        assertEquals("kratos-sender", e.senderId());
        assertEquals("01CONV", e.conversationId());
        assertEquals("01ULID", e.messageId(), "server ULID of the message");
        assertEquals("text", e.kind(), "kind is carried");
        assertEquals("привет", e.preview(), "text body becomes the preview");
        assertNull(e.senderName(), "senderName is not on the wire Message → null/omitted");
        assertNotNull(e.eventId(), "an eventId (uuid) is generated");
        assertDoesNotThrow(() -> Instant.parse(e.occurredAt()), "occurredAt is a valid RFC3339 instant");
        assertEquals(Instant.ofEpochMilli(1_770_000_000_000L).toString(), e.occurredAt());
    }

    @Test
    @DisplayName("attachment message → preview is empty (kept, not omitted); kind carried")
    void attachmentHasEmptyPreview() {
        Message msg = base()
                .payload(Message.Payload.builder().kind("attachment").body(null).build())
                .build();

        OfflineEvent e = OfflineEvent.from(msg);

        assertEquals("attachment", e.kind(), "kind is carried");
        assertEquals("", e.preview(), "attachment has an empty preview (no text), not null");
    }

    @Test
    @DisplayName("long body preview is truncated to the cap")
    void longPreviewTruncated() {
        String body = "x".repeat(OfflineEvent.PREVIEW_MAX + 50);
        Message msg = base()
                .payload(Message.Payload.builder().kind("text").body(body).build())
                .build();

        OfflineEvent e = OfflineEvent.from(msg);

        assertEquals(OfflineEvent.PREVIEW_MAX, e.preview().length(), "preview is capped");
    }

    @Test
    @DisplayName("no payload → kind and preview omitted (null)")
    void noPayload() {
        Message msg = base().payload(null).build();

        OfflineEvent e = OfflineEvent.from(msg);

        assertNull(e.kind());
        assertNull(e.preview());
    }
}
