package org.hormigas.ws.infrastructure.persistance.postgres.conversation.cache;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifies the persistence-backed {@code ConversationManager} (the Postgres adapter) so the caching
 * {@code CachingConversationManager} — the default, unqualified bean the core injects — can delegate to it.
 * The core never sees this qualifier: it injects the plain {@code ConversationManager} and transparently
 * gets the caching adapter (ADR-020).
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface PersistenceBacked {
}
