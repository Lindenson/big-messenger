package org.hormigas.ws.domain.conversation.result;

/**
 * Outcome of an idempotent provision-a-chat request.
 * <ul>
 *   <li>{@code CREATED} — a new chat was inserted (→ 201).</li>
 *   <li>{@code EXISTING} — the pair already had a chat, returned as-is (→ 200). A previously
 *       "deleted-for-me" pair is also EXISTING — NOT force-revived; the first new message reveals it.</li>
 *   <li>{@code BLOCKED} — the pair exists but is blocked; provisioning is refused so the block is never
 *       silently overridden (→ 409).</li>
 * </ul>
 */
public enum CreateOutcome { CREATED, EXISTING, BLOCKED }
