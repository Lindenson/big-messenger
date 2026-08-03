package org.hormigas.ws.domain.conversation.result;

/**
 * The resolved access of a user to a conversation — the shared membership/block check's result. How each
 * guard treats it differs: a read guard allows {@code BLOCKED} (block stops sending, not reading); the
 * send-guard refuses it.
 */
public enum Access { NOT_FOUND, NOT_MEMBER, BLOCKED, OK }
