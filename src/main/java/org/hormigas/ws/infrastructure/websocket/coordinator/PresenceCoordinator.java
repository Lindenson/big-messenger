package org.hormigas.ws.infrastructure.websocket.coordinator;

import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.core.presence.AsyncPresence;
import org.hormigas.ws.core.push.OfflinePusher;
import org.hormigas.hl.contracts.watermark.Watermark;
import org.hormigas.ws.domain.credentials.ClientData;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageType;
import org.hormigas.ws.domain.push.OfflineEvent;
import org.hormigas.ws.domain.session.ClientSession;
import org.hormigas.ws.ports.notifier.Coordinator;
import org.hormigas.ws.ports.session.SessionRegistry;

import java.util.List;
import java.util.Optional;


@Slf4j
@ApplicationScoped
public class PresenceCoordinator implements Coordinator<WebSocketConnection> {

    @Inject
    PresencePublisher publisher;

    @Inject
    AsyncPresence presence;

    @Inject
    SessionRegistry<WebSocketConnection> registry;

    @Inject
    Watermark watermark;

    @Inject
    OfflinePusher offlinePush;

    @Override
    public void join(ClientData newClient, WebSocketConnection connection) {
        try {
            evictPriorSessions(newClient.id(), connection);
            registry.register(newClient, connection);
            presence.add(newClient.id(), newClient.name(), System.currentTimeMillis());
            publisher.publishInit(newClient, registry);
            publisher.publishJoin(newClient);
            log.debug("Presence INIT coordinated for {}", newClient.id());
        } catch (Exception e) {
            log.error("Failed to coordinate INIT presence for {}", newClient.id(), e);
        }
    }

    /**
     * Single active session per user: when a new connection arrives for a client, evict any prior
     * ones. We deregister each old connection FIRST so its later {@code @OnClose → leave} is a no-op
     * (deregister returns null) — that keeps the takeover clean: the new session's presence and the
     * client's GC watermark are NOT torn down by the old socket closing.
     */
    private void evictPriorSessions(String clientId, WebSocketConnection keep) {
        List<WebSocketConnection> stale = registry.streamSessionsByClientId(clientId)
                .map(ClientSession::getSession)
                .filter(c -> !c.equals(keep))
                .toList();
        for (WebSocketConnection old : stale) {
            registry.deregister(old);
            old.close().subscribe().with(
                    x -> log.debug("Evicted prior session for {}", clientId),
                    e -> log.debug("Error closing prior session for {}: {}", clientId, e.getMessage()));
        }
    }

    @Override
    public void leave(WebSocketConnection connection) {
        try {
            Optional.ofNullable(registry.deregister(connection))
                    .map(s -> new ClientData(s.getClientId(), s.getClientName(), null, null))
                    .ifPresent(client -> {
                        fireRemove(client.id());
                        publisher.publishLeave(client);
                        long timestamp = System.currentTimeMillis();
                        presence.remove(client.id(), timestamp);
                        log.debug("Presence LEAVE coordinated for {}", client.id());
                    });
        } catch (Exception e) {
            log.error("Failed to coordinate LEAVE presence for {}", connection, e);
        }
    }

    @Override
    public void active(WebSocketConnection connection) {
        var session = registry.getSessionByConnection(connection);
        if (session != null) {
            session.updateActivity();
            session.updateSequence();
        }
    }

    /**
     * A recipient was found absent on the delivery path. Fan out to the pluggable absence handlers — each
     * is NON-BLOCKING, so calling them in sequence still lets them run concurrently (plus/minus): none
     * waits on another, and none can block or fail the delivery path that triggered this.
     */
    @Override
    public void passive(Message undelivered) {
        String clientId = undelivered.getRecipientId();
        log.debug("Absence coordinated for {}", clientId);
        long timestamp = System.currentTimeMillis();
        // (1) presence: mark the recipient offline.
        presence.remove(clientId, timestamp);
        // (2) GC watermark: a recipient found absent must ALSO stop holding the GC watermark — otherwise
        // its un-ACKed outbox rows pin safe-delete forever and the poller re-delivers them into the void.
        // Advancing here (idempotent onDisconnect) lets GC drain those rows; the recipient recovers the
        // messages via REST history-sync on reconnect. Mirrors the watermark advance in leave(), just
        // triggered by delivery feedback instead of @OnClose. Self-limiting: once GC drains the rows the
        // poller stops fetching them, so passive stops firing.
        fireRemove(clientId);
        // (3) offline push: nudge the Go notifier — durable server→client frames only.
        pushOfflineNudge(undelivered);
    }

    /** Fire-and-forget the watermark advance off the caller's thread (the pure Watermark returns a Uni). */
    private void fireRemove(String clientId) {
        watermark.remove(clientId)
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .subscribe().with(
                        ignored -> log.debug("Client {} removed from GC watermark", clientId),
                        failure -> log.error("Failed to remove client {} from GC watermark", clientId, failure));
    }

    /**
     * Offline-push absence handler: only real, durable server→client messages (CHAT_OUT, SYSTEM_OUT) warrant
     * a nudge — those are the frames a recipient would actually miss. Transient/protocol frames (presence,
     * signaling, typing, ACKs, read receipts) are not persisted and carry no user-facing content, so they
     * are skipped. {@code OfflinePusher.push} is itself fire-and-forget (coalesced + bounded).
     */
    private void pushOfflineNudge(Message message) {
        MessageType type = message.getType();
        if (type == MessageType.CHAT_OUT || type == MessageType.SYSTEM_OUT) {
            offlinePush.push(OfflineEvent.from(message));
        }
    }
}
