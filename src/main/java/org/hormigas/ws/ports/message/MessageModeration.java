package org.hormigas.ws.ports.message;

import io.smallrye.mutiny.Uni;

/**
 * Driven port for message mutability rules (UC-U21/U22): conditional delete + freeze.
 * Messages are never edited; they can be deleted only while NOT frozen, and freezing makes them
 * permanent (a contract was reached).
 */
public interface MessageModeration {

    /**
     * Delete a message — only if it exists, is not frozen, AND belongs to the caller (own-only,
     * object-level authorization). A participant may hard-delete their OWN messages, never the peer's.
     */
    Uni<DeleteOutcome> deleteMessage(String conversationId, String callerId, String messageId);

    /**
     * Delete a message addressed by its TYPED ids — the server id ({@code message_id}) and/or the
     * client-original id ({@code client_message_id}). The frontend generates its own id and, until the
     * server ACK arrives, only knows that one — so a delete may carry the client id alone, the server id
     * alone, or both. Matching rule:
     * <ul>
     *   <li><b>both given</b> → STRICT: {@code message_id = serverId AND client_message_id = clientId}
     *       (exact row, no cross-column collision);</li>
     *   <li><b>one given</b> → match that column only.</li>
     * </ul>
     * Same own-only, not-frozen rules as {@link #deleteMessage}. At least one id must be non-blank
     * (the caller validates); if neither matches, {@code NOT_FOUND}.
     */
    Uni<DeleteOutcome> deleteMessageByIds(String conversationId, String callerId, String serverId, String clientId);

    /**
     * Freeze the (currently non-frozen) messages of one order within a conversation; returns the
     * number frozen. Message-level only — there is NO chat-level freeze (UC-U22, decisions #3/#4):
     * a contract on one order must not freeze unrelated orders' messages in the same (order-agnostic)
     * chat.
     */
    Uni<Integer> freezeByOrder(String conversationId, String orderId);

    enum DeleteOutcome { DELETED, NOT_FOUND, FROZEN, FORBIDDEN }
}
