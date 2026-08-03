package org.hormigas.ws.ports.conversation;

import io.smallrye.mutiny.Uni;
import org.hormigas.ws.domain.conversation.ChatQuery;
import org.hormigas.ws.domain.conversation.ChatStats;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;

import java.util.List;

/**
 * Driven port for conversation persistence. Implemented by an infrastructure adapter.
 */
public interface ConversationManager {

    /** The conversation for a participant pair, or {@code null} if none exists. */
    Uni<Conversation> findByPair(String clientId, String masterId);

    /** A conversation by id, or {@code null}. */
    Uni<Conversation> findById(String id);

    /** Conversations the identity participates in and has NOT soft-deleted, most-recent first. */
    Uni<List<Conversation>> findByParticipant(String userId);

    /**
     * Provision the (clientId, masterId) pair in ONE query: insert if absent, otherwise return the
     * existing row as-is (reuse — a soft-deleted pair is reused too, its delete watermark untouched).
     * {@code Upserted.created} distinguishes a fresh insert (201) from a reused existing chat (200).
     */
    Uni<Upserted> upsertPair(Conversation conversation);

    /** Result of {@link #upsertPair}: the new-or-existing conversation + whether it was newly inserted. */
    record Upserted(Conversation conversation, boolean created) {}

    /** Soft-delete (hide) the conversation for the given participant only. */
    Uni<Void> hideFor(String chatId, String userId);

    // NB: no force-"revive" — a "deleted-for-me" thread is revived naturally by the first new message
    // (messageId > the delete watermark), so re-provisioning does NOT resurrect old deleted messages.

    /** Set the block flag for the given participant's side. */
    Uni<Void> setBlocked(String chatId, String userId, boolean blocked);

    /**
     * Advance each participant's read receipt to the given read-up-to id in ONE batch, monotonically
     * ({@code GREATEST} — an older id never regresses the receipt). The marks are already coalesced upstream
     * (one MAX per {@code (conversationId, readerId)}), so this is one row per unique dialog-side. The
     * write-behind persist path for read receipts (UC-U14).
     */
    Uni<Void> advanceReadReceipts(List<ReadReceiptMark> marks);

    // ── admin reads (unfiltered by participant state) ──────────────────────────

    /** All conversations matching the query's filters/sort, paged. Not visibility-filtered (admin). */
    Uni<List<Conversation>> findAll(ChatQuery query);

    /** Count of conversations matching the query's filters (paging ignored). */
    Uni<Long> count(ChatQuery query);

    /** Platform-wide aggregate counts (total, blocked). */
    Uni<ChatStats> stats();
}
