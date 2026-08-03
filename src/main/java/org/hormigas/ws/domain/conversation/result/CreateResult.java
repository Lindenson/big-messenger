package org.hormigas.ws.domain.conversation.result;

import org.hormigas.ws.domain.conversation.Conversation;

/** Result of an idempotent provision-a-chat request: the chat and its {@link CreateOutcome}. */
public record CreateResult(Conversation conversation, CreateOutcome outcome) {

    /** True only for a brand-new chat (201 vs 200). */
    public boolean created() {
        return outcome == CreateOutcome.CREATED;
    }

    /** True when provisioning was refused because the pair is blocked (409). */
    public boolean blocked() {
        return outcome == CreateOutcome.BLOCKED;
    }
}
