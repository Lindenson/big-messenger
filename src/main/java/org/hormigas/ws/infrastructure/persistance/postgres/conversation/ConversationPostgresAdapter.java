package org.hormigas.ws.infrastructure.persistance.postgres.conversation;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.domain.conversation.ChatQuery;
import org.hormigas.ws.domain.conversation.ChatStats;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.infrastructure.persistance.postgres.conversation.cache.PersistenceBacked;
import org.hormigas.ws.ports.conversation.ConversationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
@PersistenceBacked   // the raw persistence bean; the default ConversationManager is CachingConversationManager (ADR-020)
public class ConversationPostgresAdapter implements ConversationManager {

    private static final String COLS =
            "id, client_id, master_id, metadata_json, client_blocked, master_blocked, "
                    + "deleted_from_client, deleted_from_master, client_read_receipt, master_read_receipt, "
                    + "created_at, updated_at";

    @Inject
    PgPool client;

    @Override
    public Uni<Conversation> findByPair(String clientId, String masterId) {
        return client.preparedQuery(
                        "SELECT " + COLS + " FROM conversation WHERE client_id = $1 AND master_id = $2")
                .execute(Tuple.of(clientId, masterId))
                .map(rs -> firstOrNull(rs));
    }

    @Override
    public Uni<Conversation> findById(String id) {
        return client.preparedQuery("SELECT " + COLS + " FROM conversation WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rs -> firstOrNull(rs));
    }

    @Override
    public Uni<List<Conversation>> findByParticipant(String userId) {
        // A chat the caller deleted is hidden from their list only while NO message exists above their
        // delete watermark; the next message (e.g. a new order's) makes it reappear — see V10.
        return client.preparedQuery("""
                        SELECT %s FROM conversation c
                         WHERE (client_id = $1 OR master_id = $1)
                           AND NOT (client_id = $1 AND deleted_from_client IS NOT NULL
                                    AND NOT EXISTS (SELECT 1 FROM message_history m
                                                     WHERE m.conversation_id = c.id
                                                       AND m.message_id > c.deleted_from_client))
                           AND NOT (master_id = $1 AND deleted_from_master IS NOT NULL
                                    AND NOT EXISTS (SELECT 1 FROM message_history m
                                                     WHERE m.conversation_id = c.id
                                                       AND m.message_id > c.deleted_from_master))
                         ORDER BY updated_at DESC""".formatted(COLS))
                .execute(Tuple.of(userId))
                .map(rs -> {
                    List<Conversation> out = new ArrayList<>();
                    rs.forEach(row -> out.add(map(row)));
                    return out;
                });
    }

    @Override
    public Uni<Void> hideFor(String chatId, String userId) {
        // "Delete for me": set the caller's watermark to the chat's latest messageId (COALESCE to ''
        // for an empty chat so it sorts below every ULID — nothing to hide now, and the first future
        // message reappears above it). NULL stays "never deleted".
        return client.preparedQuery("""
                        UPDATE conversation SET
                            deleted_from_client = CASE WHEN client_id = $2
                                THEN COALESCE((SELECT max(message_id) FROM message_history
                                                WHERE conversation_id = $1), '')
                                ELSE deleted_from_client END,
                            deleted_from_master = CASE WHEN master_id = $2
                                THEN COALESCE((SELECT max(message_id) FROM message_history
                                                WHERE conversation_id = $1), '')
                                ELSE deleted_from_master END,
                            updated_at = now()
                         WHERE id = $1""")
                .execute(Tuple.of(chatId, userId))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> setBlocked(String chatId, String userId, boolean blocked) {
        return client.preparedQuery("""
                        UPDATE conversation SET
                            client_blocked = CASE WHEN client_id = $2 THEN $3 ELSE client_blocked END,
                            master_blocked = CASE WHEN master_id = $2 THEN $3 ELSE master_blocked END,
                            updated_at = now()
                         WHERE id = $1""")
                .execute(Tuple.of(chatId, userId, blocked))
                .replaceWithVoid();
    }

    @Override
    public Uni<Upserted> upsertPair(Conversation c) {
        // One round-trip, no ORM call-chain: try the insert; on a pair-conflict DO NOTHING keeps the
        // existing row untouched (reuse — cache/updated_at unaffected), and the UNION ALL branch returns
        // it. Exactly one row comes back; `created` says whether it was the fresh insert.
        return client.preparedQuery("""
                        WITH ins AS (
                            INSERT INTO conversation (id, client_id, master_id, metadata_json)
                            VALUES ($1, $2, $3, $4)
                            ON CONFLICT (client_id, master_id) DO NOTHING
                            RETURNING %1$s
                        )
                        SELECT %1$s, true  AS created FROM ins
                        UNION ALL
                        SELECT %1$s, false AS created FROM conversation
                         WHERE client_id = $2 AND master_id = $3 AND NOT EXISTS (SELECT 1 FROM ins)
                        """.formatted(COLS))
                .execute(Tuple.of(c.id(), c.clientId(), c.masterId(), toJson(c.metadata())))
                .map(rs -> {
                    Row row = rs.iterator().next(); // always exactly one row (insert xor existing)
                    return new Upserted(map(row), row.getBoolean("created"));
                });
    }

    // ── admin reads (unfiltered by participant state) ──────────────────────────

    @Override
    public Uni<List<Conversation>> findAll(ChatQuery q) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(q, params);
        String orderBy = switch (q.sort()) {
            case CREATED_ASC -> "created_at ASC";
            case CREATED_DESC -> "created_at DESC";
            case UPDATED_ASC -> "updated_at ASC";
            case UPDATED_DESC -> "updated_at DESC";
        };
        int limitIdx = params.size() + 1;
        int offsetIdx = params.size() + 2;
        params.add(q.limit());
        params.add(q.offset());
        String sql = "SELECT " + COLS + " FROM conversation" + where
                + " ORDER BY " + orderBy + " LIMIT $" + limitIdx + " OFFSET $" + offsetIdx;
        return client.preparedQuery(sql).execute(Tuple.from(params))
                .map(rs -> {
                    List<Conversation> out = new ArrayList<>();
                    rs.forEach(row -> out.add(map(row)));
                    return out;
                });
    }

    @Override
    public Uni<Long> count(ChatQuery q) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(q, params);
        String sql = "SELECT count(*) AS n FROM conversation" + where;
        return client.preparedQuery(sql).execute(Tuple.from(params))
                .map(rs -> rs.iterator().next().getLong("n"));
    }

    @Override
    public Uni<ChatStats> stats() {
        return client.query("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE client_blocked OR master_blocked) AS blocked
                          FROM conversation""")
                .execute()
                .map(rs -> {
                    Row r = rs.iterator().next();
                    return new ChatStats(r.getLong("total"), r.getLong("blocked"));
                });
    }

    /** Build the parameterized WHERE for an admin {@link ChatQuery}; appends bind values to {@code params}. */
    private String buildWhere(ChatQuery q, List<Object> params) {
        List<String> conds = new ArrayList<>();
        if (q.participantId() != null) {
            params.add(q.participantId());
            int i = params.size(); // a single bind, referenced on both sides of the pair
            conds.add("(client_id = $" + i + " OR master_id = $" + i + ")");
        }
        if (q.conversationId() != null) {
            params.add(q.conversationId());
            conds.add("id = $" + params.size());
        }
        if (q.blockedOnly()) {
            conds.add("(client_blocked OR master_blocked)");
        }
        if (q.createdFrom() != null) {
            params.add(OffsetDateTime.ofInstant(q.createdFrom(), ZoneOffset.UTC));
            conds.add("created_at >= $" + params.size());
        }
        if (q.createdTo() != null) {
            params.add(OffsetDateTime.ofInstant(q.createdTo(), ZoneOffset.UTC));
            conds.add("created_at < $" + params.size());
        }
        return conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
    }

    @Override
    public Uni<Void> advanceReadReceipts(List<ReadReceiptMark> marks) {
        if (marks == null || marks.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        // One row per unique (conversation, reader). CASE picks the reader's side; GREATEST keeps it
        // monotonic and treats a NULL (never-read) column as "adopt the new id" (GREATEST ignores NULLs).
        // ULIDs compare lexicographically == chronologically. executeBatch = one round-trip for the batch.
        String sql = """
                UPDATE conversation SET
                    client_read_receipt = CASE WHEN client_id = $2 THEN GREATEST(client_read_receipt, $3) ELSE client_read_receipt END,
                    master_read_receipt = CASE WHEN master_id = $2 THEN GREATEST(master_read_receipt, $3) ELSE master_read_receipt END,
                    updated_at = now()
                 WHERE id = $1
                """;
        List<Tuple> batch = new ArrayList<>(marks.size());
        for (ReadReceiptMark m : marks) {
            batch.add(Tuple.of(m.conversationId(), m.readerId(), m.readId()));
        }
        return client.preparedQuery(sql).executeBatch(batch)
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("advanceReadReceipts batch failed ({} marks)", marks.size(), e));
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private Conversation firstOrNull(RowSet<Row> rs) {
        var it = rs.iterator();
        return it.hasNext() ? map(it.next()) : null;
    }

    private Conversation map(Row row) {
        return new Conversation(
                row.getString("id"),
                row.getString("client_id"),
                row.getString("master_id"),
                fromJson(row.getJsonObject("metadata_json")),
                row.getBoolean("client_blocked"),
                row.getBoolean("master_blocked"),
                row.getString("deleted_from_client"),
                row.getString("deleted_from_master"),
                row.getString("client_read_receipt"),
                row.getString("master_read_receipt"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant()
        );
    }

    private JsonObject toJson(Map<String, String> meta) {
        JsonObject jo = new JsonObject();
        if (meta != null) meta.forEach(jo::put);
        return jo;
    }

    private Map<String, String> fromJson(JsonObject jo) {
        Map<String, String> out = new LinkedHashMap<>();
        if (jo != null) {
            jo.getMap().forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        }
        return out;
    }
}
