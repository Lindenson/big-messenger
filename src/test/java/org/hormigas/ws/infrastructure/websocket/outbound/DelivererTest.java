package org.hormigas.ws.infrastructure.websocket.outbound;

import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageType;
import org.hormigas.ws.domain.session.ClientSession;
import org.hormigas.hl.contracts.stage.StageResult;
import org.hormigas.ws.infrastructure.websocket.outbound.transformers.Transformer;
import org.hormigas.ws.infrastructure.websocket.utils.WebSocketUtils;
import org.hormigas.ws.ports.notifier.Coordinator;
import org.hormigas.ws.ports.session.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Deliverer} delivery outcomes (UC-U41). Same package so the package-private
 * @Inject fields can be wired directly without a CDI container. The Deliverer's only reaction to an
 * absent recipient is to route the undelivered message to {@code Coordinator.passive} — the absence
 * handlers themselves (presence, GC watermark, offline-push nudge) are covered by PresenceCoordinatorTest.
 */
class DelivererTest {

    private Deliverer deliverer;
    private SessionRegistry<WebSocketConnection> registry;
    private Transformer<Message, WebSocketConnection> transformer;
    private WebSocketUtils utils;
    private Coordinator<WebSocketConnection> coordinator;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        deliverer = new Deliverer();
        registry = mock(SessionRegistry.class);
        transformer = mock(Transformer.class);
        utils = mock(WebSocketUtils.class);
        coordinator = mock(Coordinator.class);
        deliverer.registry = registry;
        deliverer.transformer = transformer;
        deliverer.utils = utils;
        deliverer.coordinator = coordinator;
    }

    private Message chatOut(String recipient) {
        return Message.builder().type(MessageType.CHAT_OUT).messageId("m1").recipientId(recipient)
                .senderId("sender-1").conversationId("conv-1")
                .payload(Message.Payload.builder().kind("text").body("hi").build()).build();
    }

    @Test
    void noActiveSession_routesTheMessageToCoordinatorPassive_andSkips() {
        Message msg = chatOut("client-1");
        when(registry.streamSessionsByClientId("client-1")).thenReturn(Stream.empty());

        StageResult<Message> result = deliverer.deliver(msg).await().indefinitely();

        assertTrue(result.isSkipped());
        // The Deliverer hands the whole undelivered message to the coordinator tee; the absence handlers
        // (presence / watermark / offline-push) fan out there, not here.
        verify(coordinator).passive(msg);
    }

    @Test
    void openSessionSendFails_routesTheMessageToCoordinatorPassive_andFails() {
        Message msg = chatOut("client-1");
        WebSocketConnection conn = mock(WebSocketConnection.class);
        when(conn.isOpen()).thenReturn(true);
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().failure(new RuntimeException("socket dead")));

        ClientSession<WebSocketConnection> session =
                ClientSession.<WebSocketConnection>builder().clientId("client-1").session(conn).build();
        when(registry.streamSessionsByClientId("client-1")).thenReturn(Stream.of(session));
        when(transformer.apply(eq(msg), any())).thenReturn(msg);
        when(utils.encodeMessage(any())).thenReturn(Optional.of("{}"));

        StageResult<Message> result = deliverer.deliver(msg).await().indefinitely();

        // UC-U41: a failed live delivery to an open session also marks the recipient absent (via the coordinator)
        assertTrue(result.isFailed());
        verify(coordinator).passive(msg);
    }
}
