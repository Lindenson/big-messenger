package org.hormigas.ws.ports.history;

import io.smallrye.mutiny.Uni;

import java.util.List;

public interface History<T> {

    /**
     * A page of a conversation's messages, oldest-first, after {@code sinceMessageId} (exclusive;
     * null = from the start), capped at {@code limit} — the incremental reconnect/history-sync read
     * (UC-U50). messageId is a ULID (monotonic), so it doubles as the page cursor.
     */
    Uni<List<T>> getByConversation(String conversationId, String sinceMessageId, int limit);

    /**
     * A page for smart scroll-up: the newest {@code limit} messages strictly OLDER than
     * {@code beforeMessageId} (exclusive; null = from the very newest) and strictly newer than
     * {@code afterMessageId} (the caller's soft-delete watermark floor; null = no floor). The window is
     * selected newest-first but returned <b>oldest-first</b> so the client renders it directly. A null
     * {@code beforeMessageId} is how a client opens a chat ("give me the last N"); paging up then passes
     * the oldest id it holds as the next {@code beforeMessageId}.
     */
    Uni<List<T>> getByConversationBefore(String conversationId, String beforeMessageId, String afterMessageId, int limit);

    void addBySenderId(String clientId, T message);
}
