package org.hormigas.ws.infrastructure.websocket.outbound;

import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.hl.log.LogThrottle;
import org.hormigas.hl.log.WindowLogThrottle;
import org.hormigas.hl.contracts.stage.StageResult;
import org.hormigas.ws.ports.channel.DeliveryChannel;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.ports.session.SessionRegistry;
import org.hormigas.ws.domain.session.ClientSession;
import org.hormigas.ws.ports.notifier.Coordinator;
import org.hormigas.ws.infrastructure.websocket.outbound.transformers.Transformer;
import org.hormigas.ws.infrastructure.websocket.utils.WebSocketUtils;

import java.time.Duration;
import java.util.Optional;


import static org.hormigas.ws.infrastructure.websocket.coordinator.event.PresenceEventFactory.BROADCAST;

@Slf4j
@ApplicationScoped
public class Deliverer implements DeliveryChannel<Message> {

    @Inject
    SessionRegistry<WebSocketConnection> registry;

    @Inject
    Transformer<Message, WebSocketConnection> transformer;

    @Inject
    WebSocketUtils utils;

    @Inject
    Coordinator<WebSocketConnection> coordinator;

    // Under receiver churn these failures come in floods (StacklessClosedChannel); throttle the WARN so it
    // doesn't burn CPU on formatting exactly when overloaded. The mark-offline action stays per-failure.
    private final LogThrottle deliveryFailThrottle = new WindowLogThrottle(Duration.ofSeconds(5));
    private final LogThrottle broadcastFailThrottle = new WindowLogThrottle(Duration.ofSeconds(5));

    public Uni<StageResult<Message>> deliver(Message message) {
        if (BROADCAST.equals(message.getRecipientId())) {
            return sendBroadcast(message)
                    .replaceWith(StageResult.<Message>passed())
                    .onItem().invoke(() -> log.debug("Broadcast delivered {}", message.getMessageId()))
                    .onFailure().recoverWithItem(StageResult.passed());
        }

        // not transforming broadcast
        var unis = registry.streamSessionsByClientId(message.getRecipientId())
                .map(session -> send(session, transformer.apply(message, session)))
                .toList();

        if (unis.isEmpty()) {
            // Per-message and expected (recipient offline → held in outbox for redelivery); DEBUG, not INFO,
            // or a large offline backlog floods the log.
            log.debug("No active sessions for recipient {}", message.getRecipientId());
            // The coordinator fans this "recipient absent" signal to its non-blocking absence handlers —
            // presence, GC watermark, and the offline-push nudge (durable frames) — see PresenceCoordinator.
            coordinator.passive(message);
            return Uni.createFrom().item(StageResult.skipped());
        }

        return Uni.combine().all().unis(unis)
                .discardItems()
                .replaceWith(StageResult.<Message>passed())
                .onItem().invoke(() -> log.debug("Delivered {}", message.getMessageId()))
                // UC-U41: a live delivery to an *open* session that fails means the recipient is
                // effectively gone — mark them offline so the poller stops phantom-online retries
                // and the message is held in the outbox for redelivery on reconnect (avoids GC stall).
                .onFailure().invoke(err -> {
                    deliveryFailThrottle.record(n -> log.warn("Delivery failed x{} (last recipient {}): {}",
                            n, message.getRecipientId(), err.getMessage()));
                    coordinator.passive(message);
                })
                .onFailure().recoverWithItem(StageResult.failed());
    }


    private Uni<Void> send(ClientSession<WebSocketConnection> session, Message message) {
        WebSocketConnection conn = session.getSession();
        if (!conn.isOpen()) {
            registry.deregister(conn);
            return Uni.createFrom().voidItem();
        }

        return utils.encodeMessage(message)
                .map(encoded -> conn.sendText(encoded)
                        // Best-effort live send; the socket can close between isOpen() and sendText
                        // (a benign race). Durability is the outbox's job — this message is redelivered
                        // on reconnect. The meaningful signal (mark-offline) is logged by deliver()'s
                        // onFailure above, so this per-socket detail is DEBUG, not ERROR.
                        .onFailure().invoke(err -> log.debug("Live send to {} failed (will redeliver): {}",
                                conn.id(), err.getMessage())))
                .orElse(Uni.createFrom().voidItem());
    }


    private Uni<Void> sendBroadcast(Message message) {
        Optional<ClientSession<WebSocketConnection>> first = registry.streamAllOnlineClients()
                .filter(client -> client.getSession().isOpen())
                .findFirst();

        first.map(ClientSession::getSession).ifPresent(registry::cleanUnused);

        return first.map(ClientSession::getSession)
                .flatMap(session -> utils.encodeMessage(message)
                        .map(m -> session.broadcast().sendText(m)
                                // Best-effort broadcast to one live session; a closed-socket race here is
                                // recovered upstream and the notice is non-durable — WARN, not ERROR.
                                .onFailure().invoke(err -> broadcastFailThrottle.record(n ->
                                        log.warn("Broadcast send failed x{} (last msg {}): {}",
                                                n, message.getMessageId(), err.getMessage()))))
                )
                .orElse(Uni.createFrom().voidItem());
    }
}
