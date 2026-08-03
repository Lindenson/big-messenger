package org.hormigas.ws.domain.storage;

import java.time.Instant;

/** A short-lived presigned object-storage URL plus the method the client must use and its expiry. */
public record PresignedUrl(String url, String method, Instant expiresAt) {
    public PresignedUrl {
        java.util.Objects.requireNonNull(url, "url");
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
