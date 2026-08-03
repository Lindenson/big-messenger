package org.hormigas.ws.domain.attachment;

import org.hormigas.ws.domain.storage.PresignedUrl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UploadTicket — construction invariants")
class UploadTicketTest {

    private static final PresignedUrl URL = new PresignedUrl("http://minio/x", "PUT", Instant.EPOCH);

    @Test
    @DisplayName("attachmentId, objectKey and upload url are all required")
    void allFieldsRequired() {
        assertThrows(NullPointerException.class, () -> new UploadTicket(null, "k", URL));
        assertThrows(NullPointerException.class, () -> new UploadTicket("a1", null, URL));
        assertThrows(NullPointerException.class, () -> new UploadTicket("a1", "k", null));
    }

    @Test
    @DisplayName("a complete ticket is accepted")
    void validBuilds() {
        UploadTicket t = new UploadTicket("a1", "k1", URL);
        assertEquals("a1", t.attachmentId());
        assertSame(URL, t.upload());
    }
}
