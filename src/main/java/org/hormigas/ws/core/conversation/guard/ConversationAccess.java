package org.hormigas.ws.core.conversation.guard;

import jakarta.enterprise.context.ApplicationScoped;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.result.Access;

/**
 * The single membership/block check for a conversation, shared by every conversation-scoped guard
 * (ADR-020) — so the rule lives in exactly one place instead of being re-implemented per call site.
 * How each caller treats the result differs: a read guard ({@link ConversationGuard#guardedRead}) allows
 * {@code BLOCKED} (block stops <em>sending</em>, not reading), while the send-guard
 * ({@link ConversationGuard#evaluateSend}) refuses it.
 */
@ApplicationScoped
public class ConversationAccess {

    /** Resolve access for {@code userId} over {@code conv} (nullable = not found). */
    public Access check(Conversation conv, String userId) {
        if (conv == null) return Access.NOT_FOUND;
        if (!conv.hasParticipant(userId)) return Access.NOT_MEMBER;
        if (conv.isBlocked()) return Access.BLOCKED;
        return Access.OK;
    }
}
