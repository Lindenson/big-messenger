package org.hormigas.ws.infrastructure.rest.conversation;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;
import org.hormigas.ws.core.conversation.AdminChats;
import org.hormigas.ws.domain.conversation.ChatQuery;
import org.hormigas.ws.domain.conversation.ChatStats;
import org.hormigas.ws.domain.conversation.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Query parsing, delegation and rendering. Access control is NOT tested here: it moved to
 * {@link org.hormigas.ws.infrastructure.rest.security.AdminKeyFilter} (@AdminOnly → X-Admin-Key,
 * ADR-006) — see AdminKeyFilterTest. The resource no longer authenticates, so its methods run
 * assuming the filter already passed.
 */
@DisplayName("AdminChatResource — query parsing, delegation, rendering")
class AdminChatResourceTest {

    private AdminChats adminChats;
    private AdminChatResource resource;

    @BeforeEach
    void setUp() {
        adminChats = mock(AdminChats.class);
        resource = new AdminChatResource();
        resource.adminChats = adminChats;
        when(adminChats.list(any())).thenReturn(Uni.createFrom().item(List.<Conversation>of()));
        when(adminChats.count(any())).thenReturn(Uni.createFrom().item(0L));
        when(adminChats.stats()).thenReturn(Uni.createFrom().item(new ChatStats(0, 0)));
    }

    private int status(Uni<Response> u) {
        return u.await().indefinitely().getStatus();
    }

    private Uni<Response> list(String from, String sort) {
        return resource.list(null, null, false, from, null, sort, 0, 0, "u", "n", "ADMIN", "e");
    }

    @Test
    @DisplayName("list → 200 and delegates to the core")
    void listOk() {
        assertEquals(200, status(list(null, null)));
        verify(adminChats).list(any());
        verify(adminChats).count(any());
    }

    @Test
    @DisplayName("filters are passed through to the query (blocked + participant + sort)")
    void filtersPassThrough() {
        resource.list("client-9", null, true, null, null, "created_asc", 25, 5, "u", "n", "ADMIN", "e")
                .await().indefinitely();
        var captor = org.mockito.ArgumentCaptor.forClass(ChatQuery.class);
        verify(adminChats).list(captor.capture());
        ChatQuery q = captor.getValue();
        assertEquals("client-9", q.participantId());
        assertTrue(q.blockedOnly());
        assertEquals(org.hormigas.ws.domain.conversation.ChatSort.CREATED_ASC, q.sort());
        assertEquals(25, q.limit());
        assertEquals(5, q.offset());
    }

    @Test
    @DisplayName("a malformed timestamp → 400 (and the core is not called)")
    void badTimestamp() {
        assertEquals(400, status(list("not-a-date", null)));
        verify(adminChats, never()).list(any());
    }

    @Test
    @DisplayName("an unknown sort → 400")
    void badSort() {
        assertEquals(400, status(list(null, "sideways")));
        verify(adminChats, never()).list(any());
    }

    @Test
    @DisplayName("stats → 200")
    void statsOk() {
        when(adminChats.stats()).thenReturn(Uni.createFrom().item(new ChatStats(5, 1)));
        assertEquals(200, status(resource.stats("u", "n", "ADMIN", "e")));
    }
}
