package org.hormigas.ws.infrastructure.persistance.postgres.dto;

import java.time.Instant;

public record HistoryRow(
        String messageId,
        String conversationId,
        String senderId,
        String recipientId,
        String orderId,
        String payloadJson,
        Instant createdAt,
        String clientMessageId
) {
    /** Convenience for callers that don't carry a client-original id (no client_message_id → null). */
    public HistoryRow(String messageId, String conversationId, String senderId, String recipientId,
                      String orderId, String payloadJson, Instant createdAt) {
        this(messageId, conversationId, senderId, recipientId, orderId, payloadJson, createdAt, null);
    }
}