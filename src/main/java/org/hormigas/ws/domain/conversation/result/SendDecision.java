package org.hormigas.ws.domain.conversation.result;

import org.hormigas.ws.domain.conversation.Conversation;

/** A send check plus the resolved conversation (non-null only when {@code check == ALLOW}). */
public record SendDecision(SendCheck check, Conversation conversation) {}
