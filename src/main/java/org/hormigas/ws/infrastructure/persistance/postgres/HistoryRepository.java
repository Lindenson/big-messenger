package org.hormigas.ws.infrastructure.persistance.postgres;

import io.smallrye.mutiny.Uni;
import org.hormigas.ws.infrastructure.persistance.postgres.dto.HistoryRow;
import org.hormigas.ws.infrastructure.persistance.postgres.dto.Inserted;

import java.time.Instant;
import java.util.List;

public interface HistoryRepository {
    // ----------------------------------------------------------------------
    // insertHistoryBatch
    // ----------------------------------------------------------------------
    Uni<List<Inserted>> insertHistoryBatch(List<HistoryRow> batch);

    // ----------------------------------------------------------------------
    // deleteByIds
    // ----------------------------------------------------------------------
    Uni<Integer> deleteByIds(List<Long> ids);

    // ----------------------------------------------------------------------
    // deleteByMessageIds
    // ----------------------------------------------------------------------
    Uni<Integer> deleteByMessageIds(List<String> messageIds);

    // ----------------------------------------------------------------------
    // deleteOlderThan — normal retention class ONLY (UC-U23, decision #6).
    // Frozen messages are a separate, longer retention class and are NOT purged here; use
    // deleteFrozenOlderThan with the longer threshold instead.
    // ----------------------------------------------------------------------
    Uni<Integer> deleteOlderThan(Instant threshold);

    // ----------------------------------------------------------------------
    // deleteFrozenOlderThan — frozen retention class (≈ order-retention, longer than normal).
    // ----------------------------------------------------------------------
    Uni<Integer> deleteFrozenOlderThan(Instant threshold);

    // ----------------------------------------------------------------------
    // findByConversationSince — a page after sinceMessageId (null = from start), capped at limit.
    // ----------------------------------------------------------------------
    Uni<List<HistoryRow>> findByConversationSince(String conversationId, String sinceMessageId, int limit);

    // findByConversationBefore — the newest `limit` rows strictly older than beforeMessageId (null = from
    // newest) and strictly newer than afterMessageId (delete-watermark floor; null = none). Selected DESC.
    Uni<List<HistoryRow>> findByConversationBefore(String conversationId, String beforeMessageId,
                                                   String afterMessageId, int limit);
}
