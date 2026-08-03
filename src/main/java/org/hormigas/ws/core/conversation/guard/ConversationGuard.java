package org.hormigas.ws.core.conversation.guard;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.result.Guarded;
import org.hormigas.ws.domain.conversation.result.SendCheck;
import org.hormigas.ws.domain.conversation.result.SendDecision;
import org.hormigas.ws.ports.conversation.ConversationManager;

import java.util.function.Function;

/**
 * Conversation access guards (ADR-020) — the one place membership/block authorization lives, built on the
 * shared {@link ConversationAccess} check. Two entry points answer different questions on top of the same
 * check:
 * <ul>
 *   <li>{@link #guardedRead} — the generic read/op guard: NOT_FOUND / FORBIDDEN, else run the action.
 *       A block does NOT bar reads (history/delete/receipts stay available on a blocked chat).</li>
 *   <li>{@link #evaluateSend} / {@link #canSend} — the send-guard: a block IS a stop, and on ALLOW it
 *       returns the resolved conversation so the caller derives the authentic recipient.</li>
 * </ul>
 */
@ApplicationScoped
public class ConversationGuard {

    private final ConversationManager conversations;
    private final ConversationAccess access;

    @Inject
    public ConversationGuard(ConversationManager conversations, ConversationAccess access) {
        this.conversations = conversations;
        this.access = access;
    }

    /** Membership-guarded operation over a conversation; block does not bar it (only sending is stopped). */
    public <T> Uni<Guarded<T>> guardedRead(String chatId, String userId, Function<Conversation, Uni<T>> action) {
        return conversations.findById(chatId).flatMap(c -> switch (access.check(c, userId)) {
            case NOT_FOUND -> Uni.createFrom().item(Guarded.<T>notFound());
            case NOT_MEMBER -> Uni.createFrom().item(Guarded.<T>forbidden());
            case BLOCKED, OK -> action.apply(c).map(Guarded::ok);
        });
    }

    /** Whether {@code senderId} may send into {@code conversationId} right now (UC-H07 / FR-MSG-01). */
    public Uni<SendCheck> canSend(String conversationId, String senderId) {
        return evaluateSend(conversationId, senderId).map(SendDecision::check);
    }

    /**
     * Like {@link #canSend} but also returns the resolved {@link Conversation} on ALLOW, so the caller can
     * derive the authentic recipient (the other participant) instead of trusting a client-supplied one.
     * Delete is "delete for me" (a per-side watermark), not a messaging stop; only a block is terminal.
     */
    public Uni<SendDecision> evaluateSend(String conversationId, String senderId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Uni.createFrom().item(new SendDecision(SendCheck.NO_CONVERSATION, null));
        }
        return conversations.findById(conversationId).map(c -> switch (access.check(c, senderId)) {
            case NOT_FOUND -> new SendDecision(SendCheck.NO_CONVERSATION, null);
            case NOT_MEMBER -> new SendDecision(SendCheck.NOT_MEMBER, null);
            case BLOCKED -> new SendDecision(SendCheck.BLOCKED, null);
            case OK -> new SendDecision(SendCheck.ALLOW, c);
        });
    }
}
