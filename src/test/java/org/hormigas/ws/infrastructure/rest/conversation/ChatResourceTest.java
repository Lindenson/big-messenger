package org.hormigas.ws.infrastructure.rest.conversation;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;
import org.hormigas.ws.core.conversation.Chats;
import org.hormigas.ws.core.receipt.ReadReceiptBatcher;
import org.hormigas.ws.domain.conversation.result.CreateResult;
import org.hormigas.ws.domain.conversation.result.CreateOutcome;
import org.hormigas.ws.domain.conversation.result.Guarded;
import org.hormigas.ws.domain.conversation.HistoryPage;
import org.hormigas.ws.domain.conversation.result.GuardedOutcome;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.domain.credentials.ClientData;
import org.hormigas.ws.infrastructure.rest.history.security.TokenVerifier;
import org.hormigas.ws.ports.message.MessageModeration.DeleteOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatResource is a thin adapter: it authenticates and maps the core {@link Guarded} outcome to HTTP.
 * Membership/authorization is enforced in {@link Chats} (covered by ConversationsTest), so this
 * test only verifies the auth gate and the per-endpoint status mapping.
 */
@DisplayName("ChatResource — auth gate + Guarded→HTTP mapping")
class ChatResourceTest {

    private final Chats conversations = mock(Chats.class);
    private final TokenVerifier auth = mock(TokenVerifier.class);
    private final ReadReceiptBatcher buffer = mock(ReadReceiptBatcher.class);
    private ChatResource resource;

    private static final ClientData ALICE = new ClientData("A", "Alice", "CLIENT", "a@x");
    private static final Conversation CHAT = new Conversation(
            "chat-1", "A", "M", Map.of(), false, false, Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        resource = new ChatResource();
        resource.conversations = conversations;
        resource.auth = auth;
        resource.buffer = buffer;
    }

    private void authenticated() {
        when(auth.fromHeaders(any(), any(), any(), any())).thenReturn(Optional.of(ALICE));
    }

    private int status(Uni<Response> u) {
        return u.await().indefinitely().getStatus();
    }

    // NOTE: create's access gate (X-Admin-Key) moved to AdminKeyFilter (@AdminOnly) — covered by
    // AdminKeyFilterTest. The resource no longer returns 401/403 itself, so those cases live there.

    @Test
    @DisplayName("create with a missing participant → 400")
    void createBadRequest() {
        assertEquals(400, status(resource.create("u", "n", "SERVICE", "e",
                new ChatResource.CreateChatRequest(null, "M", Map.of()))));
    }

    @Test
    @DisplayName("create a new chat → 201, an existing one → 200 (idempotent); SERVICE/ADMIN only")
    void createNewVsExisting() {
        authenticated();
        when(conversations.createChat("A", "M", Map.of()))
                .thenReturn(Uni.createFrom().item(new CreateResult(CHAT, CreateOutcome.CREATED)));
        assertEquals(201, status(resource.create("u", "n", "SERVICE", "e",
                new ChatResource.CreateChatRequest("A", "M", Map.of()))));

        when(conversations.createChat("A", "M", Map.of()))
                .thenReturn(Uni.createFrom().item(new CreateResult(CHAT, CreateOutcome.EXISTING)));
        assertEquals(200, status(resource.create("u", "n", "ADMIN", "e",
                new ChatResource.CreateChatRequest("A", "M", Map.of()))));
    }

    @Test
    @DisplayName("create over a blocked pair → 409 (the block is not silently overridden)")
    void createOverBlockedPairConflicts() {
        authenticated();
        when(conversations.createChat("A", "M", Map.of()))
                .thenReturn(Uni.createFrom().item(new CreateResult(CHAT, CreateOutcome.BLOCKED)));
        assertEquals(409, status(resource.create("u", "n", "SERVICE", "e",
                new ChatResource.CreateChatRequest("A", "M", Map.of()))));
    }

    @Test
    @DisplayName("list chats → 200 for an authenticated caller")
    void listOk() {
        authenticated();
        when(conversations.listChats("A")).thenReturn(Uni.createFrom().item(List.of(CHAT)));
        assertEquals(200, status(resource.list("u", "n", "r", "e")));
    }

    @Test
    @DisplayName("messages: maps Guarded NOT_FOUND→404, FORBIDDEN→403, OK→200")
    void messagesGating() {
        authenticated();
        when(conversations.history(eq("missing"), eq("A"), any(), any(), any(), anyBoolean()))
                .thenReturn(Uni.createFrom().item(Guarded.notFound()));
        assertEquals(404, status(resource.messages("missing", null, null, null, false, "u", "n", "r", "e")));

        when(conversations.history(eq("c2"), eq("A"), any(), any(), any(), anyBoolean()))
                .thenReturn(Uni.createFrom().item(Guarded.forbidden()));
        assertEquals(403, status(resource.messages("c2", null, null, null, false, "u", "n", "r", "e")));

        when(conversations.history(eq("chat-1"), eq("A"), any(), any(), any(), anyBoolean()))
                .thenReturn(Uni.createFrom().item(Guarded.ok(new HistoryPage(List.of(), null))));
        assertEquals(200, status(resource.messages("chat-1", null, null, null, false, "u", "n", "r", "e")));
    }

    @Test
    @DisplayName("soft-delete maps the outcome to 204 / 404 / 403")
    void softDeleteOutcomes() {
        authenticated();
        when(conversations.hide("chat-1", "A")).thenReturn(Uni.createFrom().item(GuardedOutcome.OK));
        assertEquals(204, status(resource.softDelete("chat-1", "u", "n", "r", "e")));
        when(conversations.hide("chat-1", "A")).thenReturn(Uni.createFrom().item(GuardedOutcome.NOT_FOUND));
        assertEquals(404, status(resource.softDelete("chat-1", "u", "n", "r", "e")));
        when(conversations.hide("chat-1", "A")).thenReturn(Uni.createFrom().item(GuardedOutcome.FORBIDDEN));
        assertEquals(403, status(resource.softDelete("chat-1", "u", "n", "r", "e")));
    }

    @Test
    @DisplayName("block / unblock return 204")
    void blockUnblock() {
        authenticated();
        when(conversations.setBlocked("chat-1", "A", true)).thenReturn(Uni.createFrom().item(GuardedOutcome.OK));
        when(conversations.setBlocked("chat-1", "A", false)).thenReturn(Uni.createFrom().item(GuardedOutcome.OK));
        assertEquals(204, status(resource.block("chat-1", "u", "n", "r", "e")));
        assertEquals(204, status(resource.unblock("chat-1", "u", "n", "r", "e")));
    }

    @Test
    @DisplayName("delete message: OK+FROZEN→409, OK+DELETED→204, guard FORBIDDEN→403")
    void deleteMessageOutcomes() {
        authenticated();
        when(conversations.deleteMessage("chat-1", "A", "m1"))
                .thenReturn(Uni.createFrom().item(Guarded.ok(DeleteOutcome.FROZEN)));
        assertEquals(409, status(resource.deleteMessage("chat-1", "m1", "u", "n", "r", "e")));
        when(conversations.deleteMessage("chat-1", "A", "m1"))
                .thenReturn(Uni.createFrom().item(Guarded.ok(DeleteOutcome.DELETED)));
        assertEquals(204, status(resource.deleteMessage("chat-1", "m1", "u", "n", "r", "e")));
        when(conversations.deleteMessage("chat-1", "A", "m1"))
                .thenReturn(Uni.createFrom().item(Guarded.forbidden()));
        assertEquals(403, status(resource.deleteMessage("chat-1", "m1", "u", "n", "r", "e")));
    }

    @Test
    @DisplayName("two-id delete: both→typed (strict), one→that id, 400 when both blank")
    void deleteMessageByIdsResolution() {
        authenticated();
        // both ids present → typed delete carrying BOTH (adapter matches them strictly)
        when(conversations.deleteMessage("chat-1", "A", "server-1", "phone-1"))
                .thenReturn(Uni.createFrom().item(Guarded.ok(DeleteOutcome.DELETED)));
        assertEquals(204, status(resource.deleteMessageByIds("chat-1", "server-1", "phone-1", "u", "n", "r", "e")));
        // backend id absent → typed delete carrying only the client-original id
        when(conversations.deleteMessage("chat-1", "A", null, "phone-2"))
                .thenReturn(Uni.createFrom().item(Guarded.ok(DeleteOutcome.DELETED)));
        assertEquals(204, status(resource.deleteMessageByIds("chat-1", null, "phone-2", "u", "n", "r", "e")));
        // neither id → 400 (nothing to address), core never called
        assertEquals(400, status(resource.deleteMessageByIds("chat-1", " ", null, "u", "n", "r", "e")));
    }

    @Test
    @DisplayName("freeze requires an orderId (400), otherwise maps the guarded count to 200")
    void freezeRequiresOrderId() {
        authenticated();
        assertEquals(400, status(resource.freeze("chat-1", "u", "n", "r", "e", new ChatResource.FreezeRequest(" "))));

        when(conversations.freezeByOrder("chat-1", "A", "order-1"))
                .thenReturn(Uni.createFrom().item(Guarded.ok(2)));
        assertEquals(200, status(resource.freeze("chat-1", "u", "n", "r", "e",
                new ChatResource.FreezeRequest("order-1"))));
    }

    @Test
    @DisplayName("mark-read resolves a mark → 200 + observed into the buffer; nothing to record → 200, not observed")
    void markReadObservesResolvedMark() {
        authenticated();
        ReadReceiptMark mark = new ReadReceiptMark("chat-1", "A", "B", "01ABCDEFGHJK");
        when(conversations.resolveReadMark("chat-1", "A", "01ABCDEFGHJK"))
                .thenReturn(Uni.createFrom().item(Guarded.ok(mark)));
        assertEquals(200, status(resource.markRead("chat-1", "01ABCDEFGHJK", "u", "n", "r", "e")));
        verify(buffer).observe(mark);

        // OK with a null value = nothing to record → still 200, but never fed to the buffer.
        when(conversations.resolveReadMark("chat-1", "A", null))
                .thenReturn(Uni.createFrom().item(Guarded.ok(null)));
        assertEquals(200, status(resource.markRead("chat-1", null, "u", "n", "r", "e")));
        verify(buffer, never()).observe(null);
    }

    @Test
    @DisplayName("mark-read for a non-member → 403 and nothing observed; receipts → 200 for a member")
    void markReadForbiddenAndReceipts() {
        authenticated();
        when(conversations.receipts("chat-1", "A")).thenReturn(Uni.createFrom().item(Guarded.ok(List.of())));
        assertEquals(200, status(resource.receipts("chat-1", "u", "n", "r", "e")));

        when(conversations.resolveReadMark("c2", "A", null))
                .thenReturn(Uni.createFrom().item(Guarded.forbidden()));
        assertEquals(403, status(resource.markRead("c2", null, "u", "n", "r", "e")));
        verify(buffer, never()).observe(any());
    }
}
