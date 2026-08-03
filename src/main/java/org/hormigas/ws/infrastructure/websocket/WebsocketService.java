package org.hormigas.ws.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.*;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpClosedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.hormigas.ws.infrastructure.websocket.filter.ChannelFilter;
import org.hormigas.ws.infrastructure.websocket.filter.InboundMessageFilter;
import org.hormigas.hl.log.LogThrottle;
import org.hormigas.hl.log.WindowLogThrottle;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageType;
import org.hormigas.ws.domain.session.ClientSession;
import org.hormigas.ws.domain.validator.ValidationResult;
import org.hormigas.ws.domain.validator.Validator;
import org.hormigas.ws.infrastructure.websocket.inbound.InboundPublisher;
import org.hormigas.ws.infrastructure.websocket.utils.WebSocketUtils;
import org.hormigas.ws.ports.notifier.Coordinator;
import org.hormigas.ws.ports.session.SessionRegistry;

/**
 * Pure transport adapter for the WebSocket channel. It does only adapter-level work: deserialize the
 * frame, stamp the authenticated sender from the session (transport-auth translation — never trust the
 * client's {@code senderId}), apply the synchronous credit filter, validate, and publish into the
 * inbound pipeline. All routing and use cases (membership/recipient resolution, read receipts,
 * system-ack confirmation) live downstream in the pipeline stages, keyed by {@code MessageType} — this
 * class holds NO business logic and reaches the core only through the {@link InboundPublisher}.
 */
@Slf4j
@WebSocket(path = "/ws")
@ApplicationScoped
public class WebsocketService {

    @Inject
    InboundPublisher incomingPublisher;

    @Inject
    WebSocketUtils webSocketUtils;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Validator<Message> validator;

    @Inject
    SessionRegistry<WebSocketConnection> registry;

    @Inject
    Coordinator<WebSocketConnection> coordinator;

    @Inject
    WebSocketMetrics wsMetrics;

    private final ChannelFilter<Message, WebSocketConnection> channelFilter = new InboundMessageFilter<>();

    // Connection resets/closes arrive in floods under churn; throttle so the WARN/ERROR doesn't burn CPU on
    // formatting (and stacktraces) exactly when many sockets drop at once.
    private final LogThrottle connClosedThrottle = new WindowLogThrottle(java.time.Duration.ofSeconds(5));
    private final LogThrottle connErrorThrottle = new WindowLogThrottle(java.time.Duration.ofSeconds(5));

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        webSocketUtils.getValidatedClientData(connection)
                .ifPresentOrElse(client -> {
                            coordinator.join(client, connection);
                            wsMetrics.recordConnect();
                        },
                        () -> connection.closeAndAwait(webSocketUtils.getCloseReason())
                );
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        coordinator.leave(connection);
        wsMetrics.recordDisconnect();
    }

    /**
     * Liveness: the server auto-pings (see {@code auto-ping-interval}); a live client replies pong.
     * Refreshing activity here lets the session reaper distinguish a silent-but-alive client from a
     * dead connection (which stops ponging and is force-closed after the idle timeout).
     */
    @OnPongMessage
    public void onPong(io.vertx.core.buffer.Buffer data, WebSocketConnection connection) {
        coordinator.active(connection);
    }

    @OnTextMessage
    public Uni<Void> onMessage(String rawJson, WebSocketConnection connection) {
        log.debug("Receiving message {}", rawJson);
        try {
            ClientSession<WebSocketConnection> clientSession = registry.getSessionByConnection(connection);
            if (clientSession == null) {
                log.warn("Received message from unregistered connection: {}", connection.id());
                return Uni.createFrom().voidItem();
            }
            // Correlation context for every log line in this ingress hop (userId is the authenticated
            // sender; msgId the client's message id). Cleared in finally so nothing leaks to the next frame.
            MDC.put("userId", clientSession.getClientId());
            // Stamp the authenticated sender from the session — the client-supplied senderId is never
            // trusted (anti-impersonation, S1). Recipient resolution + membership/block checks happen
            // downstream in the pipeline (AuthorizationStage), keyed by MessageType.
            Message message = objectMapper.readValue(rawJson, Message.class)
                    .toBuilder().senderId(clientSession.getClientId()).build();
            MDC.put("msgId", String.valueOf(message.getMessageId()));
            wsMetrics.recordReceived(message.getType() != null ? message.getType().name() : "UNKNOWN");

            if (!channelFilter.filter(message, clientSession)) {
                wsMetrics.recordCreditsExhausted();
                log.warn("Message (id={}): {} filtered out", message.getMessageId(), message);
                return Uni.createFrom().voidItem();
            }
            ValidationResult validated = validator.validate(message);
            if (!validated.isValid()) {
                log.warn("Invalid message (id={}): {}", message.getMessageId(), validated.errors());
                return Uni.createFrom().voidItem();
            }
            coordinator.active(connection);

            // F3: a message dropped at ingress (queue full) must not be silently lost — tell the sender.
            if (!incomingPublisher.publish(message)) {
                notifyOverloaded(connection, message);
            }
        } catch (Exception e) {
            log.error("Invalid message format: {}", rawJson, e);
        } finally {
            MDC.clear();
        }
        return Uni.createFrom().voidItem();
    }

    /**
     * Tell the sender its message was rejected at ingress (queue full / overload) so it can retry —
     * a SERVICE_OUT carrying the rejected messageId as correlationId. Fire-and-forget (UC-H08).
     */
    private void notifyOverloaded(WebSocketConnection connection, Message rejected) {
        if (!wsMetrics.allowOverloadNotice()) {
            return; // shed the notice under heavy overload — the drop itself is already counted
        }
        Message notice = overloadNotice(rejected.getMessageId());
        webSocketUtils.encodeMessage(notice).ifPresent(json ->
                connection.sendText(json).subscribe().with(
                        x -> {},
                        e -> log.debug("Failed to send overload notice: {}", e.getMessage())));
    }

    /** The ingress-reject notice (UC-H08): a {@code SERVICE_OUT} carrying the rejected messageId as
     *  correlationId so the sender can correlate and retry. Pure/deterministic — extracted for testing. */
    static Message overloadNotice(String rejectedMessageId) {
        return Message.builder()
                .type(MessageType.SERVICE_OUT)
                .correlationId(rejectedMessageId)
                .payload(Message.Payload.builder().kind("event").body("overloaded").build())
                .serverTimestamp(System.currentTimeMillis())
                .build();
    }

    @OnError
    public void onError(WebSocketConnection connection, Throwable throwable) {
        boolean closed = throwable instanceof HttpClosedException;
        wsMetrics.recordConnectionError(closed);
        if (closed) {
            connClosedThrottle.record(n -> log.warn("WebSocket connections closed x{} (last {})", n, connection.id()));
        } else {
            connErrorThrottle.record(n -> log.error("WebSocket connection errors x{} (last {}): {}",
                    n, connection.id(), throwable.getMessage()));
        }
    }
}
