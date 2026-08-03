package org.hormigas.ws.domain.conversation;

import org.hormigas.ws.domain.message.Message;

import java.util.List;

/**
 * A cursor-paginated history page plus the PEER's read receipt — the "read up to" messageId of the other
 * participant, resolved join-free from the already-loaded conversation row on the guarded read path. A
 * message the caller sent is READ (✓✓) iff its {@code messageId <= peerLastReadId}; {@code peerLastReadId}
 * is {@code null} when the peer has read nothing yet.
 */
public record HistoryPage(List<Message> messages, String peerLastReadId) {}
