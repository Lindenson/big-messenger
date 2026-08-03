package org.hormigas.ws.domain.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PresignedUrl — construction invariants")
class PresignedUrlTest {

    @Test
    @DisplayName("url, method and expiry are all required")
    void allFieldsRequired() {
        assertThrows(NullPointerException.class, () -> new PresignedUrl(null, "PUT", Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new PresignedUrl("http://x", null, Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new PresignedUrl("http://x", "PUT", null));
    }

    @Test
    @DisplayName("a complete presigned url is accepted")
    void validBuilds() {
        PresignedUrl u = new PresignedUrl("http://minio/x", "PUT", Instant.EPOCH);
        assertEquals("PUT", u.method());
    }
}
