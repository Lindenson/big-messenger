package org.hormigas.ws.domain.conversation;

/**
 * One coalesced read-progress observation: {@code readerId} has read up to {@code readId} (a ULID message
 * cursor) in {@code conversationId}; {@code peerId} is the other participant, notified via a coalesced
 * {@code READ_OUT}. Framework-free domain record. Upstream the buffer keeps only the MAX {@code readId} per
 * {@code (conversationId, readerId)}, so a batch of these carries only unique dialog-sides.
 */
public record ReadReceiptMark(String conversationId, String readerId, String peerId, String readId) {}
