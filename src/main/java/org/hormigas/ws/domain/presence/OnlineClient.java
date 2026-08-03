package org.hormigas.ws.domain.presence;

public record OnlineClient(String id, String name, long timestamp) {
    /** Invariant: a presence entry always identifies a client. */
    public OnlineClient {
        java.util.Objects.requireNonNull(id, "client id");
    }
}
