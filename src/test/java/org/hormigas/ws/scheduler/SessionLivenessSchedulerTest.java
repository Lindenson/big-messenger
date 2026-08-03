package org.hormigas.ws.scheduler;

import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.hormigas.ws.core.presence.AsyncPresence;
import org.hormigas.ws.domain.credentials.ClientData;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.session.ClientSession;
import org.hormigas.ws.ports.session.SessionRegistry;
import org.hormigas.hl.contracts.tetris.TetrisMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SessionLivenessScheduler — reaps idle + overloaded connections (FR-PRES-03)")
@SuppressWarnings("unchecked")
class SessionLivenessSchedulerTest {

    private final SessionRegistry<WebSocketConnection> registry = mock(SessionRegistry.class);
    private final TetrisMarker tetris = mock(TetrisMarker.class);
    private final AsyncPresence presence = mock(AsyncPresence.class);

    private SessionLivenessScheduler scheduler() {
        SessionLivenessScheduler s = new SessionLivenessScheduler();
        s.registry = registry;
        s.tetrisMarker = tetris;
        s.presence = presence;
        s.idleTimeoutMs = 35_000;
        s.maxPending = 1000;
        return s;
    }

    private ClientSession<WebSocketConnection> session(WebSocketConnection conn, long lastActiveAt) {
        return ClientSession.<WebSocketConnection>builder()
                .clientId("c").session(conn).lastActiveAt(lastActiveAt).build();
    }

    @Test
    @DisplayName("a stale, open connection is force-closed; a fresh one is left alone")
    void closesStaleKeepsFresh() {
        WebSocketConnection stale = mock(WebSocketConnection.class);
        WebSocketConnection fresh = mock(WebSocketConnection.class);
        when(stale.isOpen()).thenReturn(true);
        when(stale.close()).thenReturn(Uni.createFrom().voidItem());
        when(fresh.isOpen()).thenReturn(true);

        long now = System.currentTimeMillis();
        when(registry.streamAllOnlineClients()).thenReturn(Stream.of(
                session(stale, now - 1_000_000),  // long idle
                session(fresh, now)));             // active

        scheduler().reap();

        verify(stale).close();
        verify(fresh, never()).close();
    }

    @Test
    @DisplayName("an already-closed stale connection is not closed again")
    void skipsAlreadyClosed() {
        WebSocketConnection dead = mock(WebSocketConnection.class);
        when(dead.isOpen()).thenReturn(false);
        when(registry.streamAllOnlineClients())
                .thenReturn(Stream.of(session(dead, System.currentTimeMillis() - 1_000_000)));

        scheduler().reap();

        verify(dead, never()).close();
    }

    @Test
    @DisplayName("an overloaded client (pending > max) is force-disconnected so its pending state is freed")
    void reapsOverloadedClient() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        when(conn.isOpen()).thenReturn(true);
        when(conn.close()).thenReturn(Uni.createFrom().voidItem());
        when(tetris.findHeavyClients(eq(1000), anyInt()))
                .thenReturn(Uni.createFrom().item(List.of("overloaded-client")));
        when(registry.streamSessionsByClientId("overloaded-client"))
                .thenReturn(Stream.of(session(conn, System.currentTimeMillis())));

        scheduler().reapOverloaded();

        verify(tetris).findHeavyClients(eq(1000), anyInt());
        verify(conn).close();
    }

    @Test
    @DisplayName("no overloaded clients → nothing is closed")
    void reapOverloadedNoop() {
        when(tetris.findHeavyClients(anyInt(), anyInt())).thenReturn(Uni.createFrom().item(List.of()));
        scheduler().reapOverloaded();
        verify(registry, never()).streamSessionsByClientId(any());
    }

    @Test
    @DisplayName("presence refresh re-asserts every local online session into presence (Redis self-heal)")
    void refreshPresenceReAddsLocalSessions() {
        when(registry.getAllOnlineClients()).thenReturn(List.of(
                new ClientData("u-1", "Alice", "CLIENT", "a@x"),
                new ClientData("u-2", "Bob", "MASTER", "b@x")));

        scheduler().refreshPresence();

        verify(presence).add(eq("u-1"), eq("Alice"), anyLong());
        verify(presence).add(eq("u-2"), eq("Bob"), anyLong());
    }

    @Test
    @DisplayName("presence refresh with no online clients adds nothing")
    void refreshPresenceEmptyNoop() {
        when(registry.getAllOnlineClients()).thenReturn(List.of());
        scheduler().refreshPresence();
        verify(presence, never()).add(any(), any(), anyLong());
    }
}
