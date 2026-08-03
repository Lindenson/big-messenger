package org.hormigas.ws.core.conversation;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hormigas.ws.domain.conversation.HistoryPage;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.result.CreateResult;
import org.hormigas.ws.domain.conversation.result.CreateOutcome;
import org.hormigas.ws.domain.conversation.result.Guarded;
import org.hormigas.ws.domain.conversation.result.GuardedOutcome;
import org.hormigas.ws.domain.conversation.result.SendCheck;
import org.hormigas.ws.domain.conversation.result.SendDecision;
import org.hormigas.ws.domain.generator.IdGenerator;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.core.conversation.guard.ConversationGuard;
import org.hormigas.ws.ports.conversation.ConversationManager;
import org.hormigas.ws.ports.history.History;
import org.hormigas.ws.ports.message.MessageModeration;
import org.hormigas.ws.ports.message.ReadReceipts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The conversation use cases (core): create/query chats and the membership-guarded operations on a
 * chat (history read, moderation, read receipts). Trigger-agnostic — invoked by the REST adapter and
 * the Order-event adapter over these same operations. Authorization (membership/block) lives here, in
 * the shared {@link #guardedRead} guard — never in an adapter.
 */
@ApplicationScoped
public class Conversations implements Chats {

    /** The single conversation port. A caching adapter fronts Postgres (ADR-020) — the core is
     *  cache-agnostic: no second port, no explicit invalidation here. */
    @Inject
    ConversationManager repository;

    /** Membership/block guards (ADR-020) — the one place access authorization lives. */
    @Inject
    ConversationGuard guard;

    @Inject
    IdGenerator idGenerator;

    @Inject
    History<Message> history;

    @Inject
    MessageModeration moderation;

    @Inject
    ReadReceipts receipts;

    /** Default history page size when the caller doesn't specify one. */
    public static final int DEFAULT_HISTORY_LIMIT = 200;
    /** Hard cap so a caller cannot request an unbounded page. */
    public static final int MAX_HISTORY_LIMIT = 500;

    /** Idempotent provision in ONE round-trip: insert a new chat, or reuse the existing pair, or refuse a
     *  blocked one. Reuse keeps the per-side delete watermark untouched (#4b — the client trims history by
     *  the boundary; a deleted pair is reused, not duplicated and not force-revived). */
    public Uni<CreateResult> createChat(String clientId, String masterId, Map<String, String> metadata) {
        Instant now = Instant.now();
        Conversation candidate = new Conversation(
                idGenerator.generateId(), clientId, masterId,
                metadata != null ? metadata : Map.of(), false, false, now, now);
        return repository.upsertPair(candidate).map(u -> {
            if (u.created()) return new CreateResult(u.conversation(), CreateOutcome.CREATED);
            // reused existing pair: refuse over a block (#3 → 409); else return as-is (#4b reuse).
            return new CreateResult(u.conversation(),
                    u.conversation().isBlocked() ? CreateOutcome.BLOCKED : CreateOutcome.EXISTING);
        });
    }

    public Uni<List<Conversation>> listChats(String participantId) {
        return repository.findByParticipant(participantId);
    }

    public Uni<Conversation> findById(String id) {
        return repository.findById(id);
    }

    /**
     * Resolve the conversation for a participant pair. Used by event adapters that key by pair
     * (e.g. freeze-on-contract), since cross-service events carry {@code (clientId, masterId)},
     * not the messenger's internal conversation id.
     */
    public Uni<Conversation> findByPair(String clientId, String masterId) {
        return repository.findByPair(clientId, masterId);
    }

    /**
     * Delete the chat for the caller ("delete for me"): sets the caller's delete watermark to the
     * chat's latest messageId, so their history/list is floored there until newer messages arrive.
     * Membership-checked; invalidates the cache.
     */
    public Uni<GuardedOutcome> hide(String chatId, String userId) {
        return guarded(chatId, userId, () -> repository.hideFor(chatId, userId));
    }

    /** Block/unblock the peer for the caller; membership-checked. (Cache invalidation is write-through in
     *  the caching adapter — ADR-020 — not a core concern.) */
    public Uni<GuardedOutcome> setBlocked(String chatId, String userId, boolean blocked) {
        return guarded(chatId, userId, () -> repository.setBlocked(chatId, userId, blocked));
    }

    private Uni<GuardedOutcome> guarded(String chatId, String userId, Supplier<Uni<Void>> action) {
        return guard.guardedRead(chatId, userId, conv -> action.get()).map(Guarded::outcome);
    }

    /**
     * Conversation-scoped, cursor-paginated history read (UC-U50), membership-guarded.
     *
     * <p>Soft-delete (D3, "delete for me"): each participant's read is floored at their own delete
     * watermark — messages at/below it are hidden for that side only (the peer and the admin path are
     * unaffected). The effective cursor is {@code max(since, myWatermark)}, so deletion composes with
     * normal pagination. {@code includeDeleted} bypasses the watermark (a participant's "show deleted"
     * / the admin view) and reads from {@code since} alone. The admin history path does not go through
     * this guard and always reads unfiltered.
     */

    // что за дичь. сделать на 2 тонких метода публичных метода и один приватный без дублирования
    // кроме того судя по работе с фронтендом удаление и восстановление сессии не работет нормально
    // после переоткрытия ранее удаленной читаются все сообщения а не с границы удаления
    public Uni<Guarded<HistoryPage>> history(String chatId, String userId, String since, String before,
                                             Integer limit, boolean includeDeleted) {
        int pageSize = limit == null ? DEFAULT_HISTORY_LIMIT : Math.min(Math.max(1, limit), MAX_HISTORY_LIMIT);
        // Thin dispatcher: a `since` cursor means forward catch-up; otherwise backward scroll-up (the default).
        // peerLastReadId comes join-free from the already-loaded conversation row (guardedRead).
        return guard.guardedRead(chatId, userId, conv -> {
            Uni<List<Message>> page = (since != null && !since.isBlank())
                    ? forwardPage(chatId, conv, userId, since, includeDeleted, pageSize)
                    : backwardPage(chatId, conv, userId, before, includeDeleted, pageSize);
            return page.map(msgs -> new HistoryPage(msgs, conv.peerReceiptFor(userId)));
        });
    }

    /** FORWARD catch-up (reconnect / history-sync): messages newer than max(since, the caller's delete-watermark). */
    private Uni<List<Message>> forwardPage(String chatId, Conversation conv, String userId,
                                           String since, boolean includeDeleted, int pageSize) {
        String floor = includeDeleted ? since : laterCursor(since, conv.deleteCursorFor(userId));
        return history.getByConversation(chatId, floor, pageSize);
    }

    /** BACKWARD scroll-up (chat-open + paging up): the newest pageSize messages older than {@code before}
     *  (blank/null = from the very newest), floored at the caller's soft-delete watermark. */
    private Uni<List<Message>> backwardPage(String chatId, Conversation conv, String userId,
                                            String before, boolean includeDeleted, int pageSize) {
        String watermark = includeDeleted ? null : conv.deleteCursorFor(userId);
        String beforeCursor = (before == null || before.isBlank()) ? null : before;
        return history.getByConversationBefore(chatId, beforeCursor, watermark, pageSize);
    }

    /** The later of two ULID cursors (lexicographic == chronological); null means "no floor". */
    private static String laterCursor(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Delete a message (only if it exists and is not frozen), membership-guarded (UC-U21). */
    public Uni<Guarded<MessageModeration.DeleteOutcome>> deleteMessage(String chatId, String userId, String messageId) {
        return guard.guardedRead(chatId, userId, conv -> moderation.deleteMessage(chatId, userId, messageId));
    }

    /** Delete a message addressed by its typed ids (server and/or client) — both = strict match, one = by
     *  that id (UC-U21). Membership-guarded; own-only + not-frozen enforced in the adapter. */
    public Uni<Guarded<MessageModeration.DeleteOutcome>> deleteMessage(String chatId, String userId,
                                                                       String serverId, String clientId) {
        return guard.guardedRead(chatId, userId, conv -> moderation.deleteMessageByIds(chatId, userId, serverId, clientId));
    }

    /** Freeze that order's messages, membership-guarded; returns the count frozen (UC-U22). */
    public Uni<Guarded<Integer>> freezeByOrder(String chatId, String userId, String orderId) {
        return guard.guardedRead(chatId, userId, conv -> moderation.freezeByOrder(chatId, orderId));
    }

    /**
     * Resolve an authorized read observation for the read-receipt buffer to coalesce (membership-guarded,
     * peer resolved, {@code lastReadId} validated). Returns {@code OK} with a {@code null} value when there
     * is nothing to record (missing/invalid {@code lastReadId}); {@code NOT_FOUND}/{@code FORBIDDEN}
     * otherwise. Does NOT touch the buffer — feeding it is a pipeline concern owned by the WS stage / REST
     * adapter; this use-case does only the conversation-domain resolve.
     */
    public Uni<Guarded<ReadReceiptMark>> resolveReadMark(String chatId, String userId, String lastReadId) {
        return guard.guardedRead(chatId, userId, conv -> {
            // client-supplied read-up-to id (ULID); light validation — non-blank + plausible length.
            if (lastReadId == null || lastReadId.isBlank() || lastReadId.length() < 10) {
                return Uni.createFrom().<ReadReceiptMark>nullItem();
            }
            String peer = userId.equals(conv.clientId()) ? conv.masterId() : conv.clientId();
            return Uni.createFrom().item(new ReadReceiptMark(chatId, userId, peer, lastReadId));
        });
    }

    /** Per-message status for the chat, membership-guarded. */
    public Uni<Guarded<List<ReadReceipts.Receipt>>> receipts(String chatId, String userId) {
        return guard.guardedRead(chatId, userId, conv -> receipts.receipts(chatId));
    }

    /**
     * Whether {@code senderId} may send into conversation {@code conversationId} right now: the
     * conversation must exist, the sender must be a participant, neither side may have deleted the
     * chat (terminal close), and neither side may have blocked the other (UC-H07 / FR-MSG-01).
     * Enforced on the WS send path.
     */
    public Uni<SendCheck> canSend(String conversationId, String senderId) {
        return guard.canSend(conversationId, senderId);
    }

    /**
     * Like {@link #canSend} but also returns the resolved {@link Conversation} when the check is
     * {@link SendCheck#ALLOW}, so the caller can derive the authentic recipient (the other participant)
     * instead of trusting a client-supplied {@code recipientId}. The conversation is {@code null} for
     * any non-ALLOW result.
     */
    public Uni<SendDecision> evaluateSend(String conversationId, String senderId) {
        return guard.evaluateSend(conversationId, senderId);
    }

    // Result types (CreateResult, SendDecision, Guarded, GuardedOutcome, SendCheck) are declared on the
    // Chats driving port and inherited here — the core never leaks its own types outward.
}
