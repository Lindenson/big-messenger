package org.hormigas.ws.domain.attachment;

import org.hormigas.ws.domain.attachment.Attachment.AttachmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Attachment — construction invariants")
class AttachmentTest {

    private Attachment valid(String id, String conv, String uploader, String key, AttachmentStatus status) {
        return new Attachment(id, conv, uploader, key, "f.png", "image/png", 10L, status,
                Instant.EPOCH, null);
    }

    @Test
    @DisplayName("a fully specified attachment is accepted")
    void validAttachmentBuilds() {
        Attachment a = valid("a1", "c1", "u1", "k1", AttachmentStatus.PENDING);
        assertEquals("a1", a.id());
        assertEquals(AttachmentStatus.PENDING, a.status());
    }

    @Test
    @DisplayName("sizeBytes may be null (not yet known at PENDING)")
    void sizeBytesNullableAtPending() {
        assertDoesNotThrow(() -> new Attachment("a1", "c1", "u1", "k1", "f", "t", null,
                AttachmentStatus.PENDING, Instant.EPOCH, null));
    }

    @Test
    @DisplayName("null id / conversationId / uploaderId / objectKey / status are rejected")
    void requiredFieldsRejectNull() {
        assertThrows(NullPointerException.class, () -> valid(null, "c1", "u1", "k1", AttachmentStatus.PENDING));
        assertThrows(NullPointerException.class, () -> valid("a1", null, "u1", "k1", AttachmentStatus.PENDING));
        assertThrows(NullPointerException.class, () -> valid("a1", "c1", null, "k1", AttachmentStatus.PENDING));
        assertThrows(NullPointerException.class, () -> valid("a1", "c1", "u1", null, AttachmentStatus.PENDING));
        assertThrows(NullPointerException.class, () -> valid("a1", "c1", "u1", "k1", null));
    }
}
