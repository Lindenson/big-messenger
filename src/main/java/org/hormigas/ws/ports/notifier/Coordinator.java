package org.hormigas.ws.ports.notifier;

import org.hormigas.ws.domain.credentials.ClientData;
import org.hormigas.ws.domain.message.Message;

public interface Coordinator<T> {
    void join(ClientData newClient, T connection);
    void leave(T connection);
    void active(T connection);

    /**
     * Signal that a recipient was found ABSENT on the delivery path (no live session, or a dead socket).
     * The coordinator fans this to its pluggable, non-blocking absence handlers — presence, GC watermark
     * and the offline-push nudge — which run concurrently. Carries the undelivered {@link Message} (not just
     * the id) so the offline-push handler can build its event; presence/watermark use its recipientId.
     */
    void passive(Message undelivered);
}
