package org.hormigas.ws.core.conversation;

import io.smallrye.mutiny.Uni;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.result.CreateResult;
import org.hormigas.ws.domain.conversation.result.Guarded;
import org.hormigas.ws.domain.conversation.result.GuardedOutcome;
import org.hormigas.ws.domain.conversation.result.SendCheck;
import org.hormigas.ws.domain.conversation.result.SendDecision;
import org.hormigas.ws.domain.conversation.HistoryPage;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.ports.message.MessageModeration.DeleteOutcome;
import org.hormigas.ws.ports.message.ReadReceipts.Receipt;

import java.util.List;
import java.util.Map;

/**
 * Driving port (port-IN) for the chat/conversation use cases. Inbound adapters (REST, Kafka, WS)
 * depend on this interface — never on the concrete core implementation — so the outside reaches the
 * core only through a port, with no direct or cyclic infra→core dependency. The core
 * ({@code core.conversation.Conversations}) provides the implementation. Result/value types are
 * {@code domain} types (see {@code domain.conversation}); the port merely references them.
 */
public interface Chats {

    /** Idempotent create: returns the existing chat for the pair, or a new one. */
    Uni<CreateResult> createChat(String clientId, String masterId, Map<String, String> metadata);

    /** The caller's chats (excluding their soft-deleted ones). */
    Uni<List<Conversation>> listChats(String participantId);

    /** Resolve a chat by id (null if absent). */
    Uni<Conversation> findById(String id);

    /** Resolve a chat by participant pair (used by pair-keyed event adapters). */
    Uni<Conversation> findByPair(String clientId, String masterId);

    /** Soft-delete (hide) the chat for the caller; membership-checked. */
    Uni<GuardedOutcome> hide(String chatId, String userId);

    /** Block/unblock the peer for the caller; membership-checked. */
    Uni<GuardedOutcome> setBlocked(String chatId, String userId, boolean blocked);

    /** Whether the sender may send into the chat right now (member + not blocked). */
    Uni<SendCheck> canSend(String conversationId, String senderId);

    /** Like {@link #canSend} but also returns the resolved chat on ALLOW (to derive the recipient). */
    Uni<SendDecision> evaluateSend(String conversationId, String senderId);

    /**
     * Conversation-scoped, cursor-paginated history read (UC-U50), membership-guarded. Floored at the
     * caller's delete watermark unless {@code includeDeleted} ("delete for me" — see {@link Conversation}).
     */
    Uni<Guarded<HistoryPage>> history(String chatId, String userId, String since, String before, Integer limit,
                                      boolean includeDeleted);

    /** Delete a message (only if it exists and is not frozen), membership-guarded (UC-U21). */
    Uni<Guarded<DeleteOutcome>> deleteMessage(String chatId, String userId, String messageId);

    /** Delete a message addressed by its typed ids — server and/or client id: both = strict match, one =
     *  by that id (the phone knows only its client id before the ACK). Membership-guarded (UC-U21). */
    Uni<Guarded<DeleteOutcome>> deleteMessage(String chatId, String userId, String serverId, String clientId);

    /** Freeze that order's messages, membership-guarded; returns the count frozen (UC-U22). */
    Uni<Guarded<Integer>> freezeByOrder(String chatId, String userId, String orderId);

    /** Resolve an authorized read observation (membership-guarded, peer + lastReadId validated) for the
     *  read-receipt buffer; OK value is {@code null} when there is nothing to record. */
    Uni<Guarded<ReadReceiptMark>> resolveReadMark(String chatId, String userId, String lastReadId);

    /** Per-message status for the chat, membership-guarded. */
    Uni<Guarded<List<Receipt>>> receipts(String chatId, String userId);
}
