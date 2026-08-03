package org.hormigas.ws.infrastructure.persistance.postgres.history;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.SqlResult;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.infrastructure.persistance.postgres.HistoryRepository;
import org.hormigas.ws.infrastructure.persistance.postgres.dto.HistoryRow;
import org.hormigas.ws.infrastructure.persistance.postgres.dto.Inserted;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@ApplicationScoped
public class HistoryPostgresRepository implements HistoryRepository {

    @Inject
    PgPool client;

    // ----------------------------------------------------------------------
    // insertHistoryBatch
    // ----------------------------------------------------------------------
    @Override
    public Uni<List<Inserted>> insertHistoryBatch(List<HistoryRow> batch) {
        if (batch == null || batch.isEmpty()) return Uni.createFrom().item(Collections.emptyList());

        StringBuilder sql = new StringBuilder("""
                INSERT INTO message_history
                 (message_id, conversation_id, sender_id, recipient_id, order_id, payload_json, created_at, client_message_id)
                VALUES
                """);

        List<Object> flat = new ArrayList<>();

        for (int i = 0; i < batch.size(); i++) {
            HistoryRow h = batch.get(i);
            if (i > 0) sql.append(",");
            int p = flat.size() + 1;
            sql.append("(")
                    .append("$").append(p).append(",")     // message_id
                    .append("$").append(p + 1).append(",") // conversation_id
                    .append("$").append(p + 2).append(",") // sender_id
                    .append("$").append(p + 3).append(",") // recipient_id
                    .append("$").append(p + 4).append(",") // order_id
                    .append("$").append(p + 5).append("::jsonb,") // payload_json
                    .append("$").append(p + 6).append(",") // created_at
                    .append("$").append(p + 7)             // client_message_id
                    .append(")");
            flat.add(h.messageId());
            flat.add(h.conversationId());
            flat.add(h.senderId());
            flat.add(h.recipientId());
            flat.add(h.orderId());
            flat.add(h.payloadJson());
            flat.add(h.createdAt().atOffset(ZoneOffset.UTC));
            flat.add(h.clientMessageId());
        }

        sql.append(" RETURNING id, message_id");

        Tuple params = Tuple.tuple();
        flat.forEach(params::addValue);

        return client.preparedQuery(sql.toString())
                .execute(params)
                .onItem()
                .transform(rows -> {
                    List<Inserted> out = new ArrayList<>();
                    for (Row r : rows) {
                        out.add(new Inserted(r.getLong("id"), r.getString("message_id")));
                    }
                    return out;
                });
    }

    // ----------------------------------------------------------------------
    // deleteByIds
    // ----------------------------------------------------------------------
    @Override
    public Uni<Integer> deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Uni.createFrom().item(0);
        String sql = "DELETE FROM message_history WHERE id = ANY($1)";
        return client.preparedQuery(sql)
                .execute(Tuple.of(toLongArray(ids)))
                .onItem().transform(SqlResult::rowCount);
    }

    // ----------------------------------------------------------------------
    // deleteByMessageIds
    // ----------------------------------------------------------------------
    @Override
    public Uni<Integer> deleteByMessageIds(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return Uni.createFrom().item(0);
        String sql = "DELETE FROM message_history WHERE message_id = ANY($1)";
        return client.preparedQuery(sql)
                .execute(Tuple.of(toStringArray(messageIds)))
                .onItem().transform(SqlResult::rowCount);
    }

    // ----------------------------------------------------------------------
    // deleteOlderThan
    // ----------------------------------------------------------------------
    @Override
    public Uni<Integer> deleteOlderThan(Instant threshold) {
        // Normal retention class only — frozen messages have their own longer TTL (decision #6),
        // so a contract's frozen evidence is never purged by the regular-history sweep.
        String sql = "DELETE FROM message_history WHERE created_at < $1 AND frozen = FALSE";
        return client.preparedQuery(sql)
                .execute(Tuple.of(threshold.atOffset(ZoneOffset.UTC)))
                .onItem().transform(SqlResult::rowCount);
    }

    @Override
    public Uni<Integer> deleteFrozenOlderThan(Instant threshold) {
        String sql = "DELETE FROM message_history WHERE created_at < $1 AND frozen = TRUE";
        return client.preparedQuery(sql)
                .execute(Tuple.of(threshold.atOffset(ZoneOffset.UTC)))
                .onItem().transform(SqlResult::rowCount);
    }

    @Override
    public Uni<List<HistoryRow>> findByConversationSince(String conversationId, String sinceMessageId, int limit) {
        // messageId is a ULID (monotonic) → it is both the sort key and the page cursor. The
        // ($2 IS NULL OR ...) form lets a null cursor mean "from the start".
        String sql = """
                SELECT * FROM message_history
                 WHERE conversation_id = $1 AND ($2::text IS NULL OR message_id > $2)
                 ORDER BY message_id ASC
                 LIMIT $3
                """;
        return client.preparedQuery(sql)
                .execute(Tuple.of(conversationId, sinceMessageId, limit))
                .onItem().transform(rows -> mapToHistoryRows(rows.iterator()));
    }

    @Override
    public Uni<List<HistoryRow>> findByConversationBefore(String conversationId, String beforeMessageId,
                                                          String afterMessageId, int limit) {
        // Smart scroll-up: the newest `limit` rows in the (afterMessageId, beforeMessageId) open window —
        // beforeMessageId is the exclusive UPPER bound (null = from the newest), afterMessageId the exclusive
        // LOWER bound (the caller's delete watermark; null = none). Selected DESC to take the newest first.
        String sql = """
                SELECT * FROM message_history
                 WHERE conversation_id = $1
                   AND ($2::text IS NULL OR message_id < $2)
                   AND ($3::text IS NULL OR message_id > $3)
                 ORDER BY message_id DESC
                 LIMIT $4
                """;
        return client.preparedQuery(sql)
                .execute(Tuple.of(conversationId, beforeMessageId, afterMessageId, limit))
                .onItem().transform(rows -> mapToHistoryRows(rows.iterator()));
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------
    private static Long[] toLongArray(List<Long> ids) {
        return ids.toArray(new Long[0]);
    }

    private static String[] toStringArray(List<String> ids) {
        return ids.toArray(new String[0]);
    }

    private static List<HistoryRow> mapToHistoryRows(RowIterator<Row> rows) {
        List<HistoryRow> list = new ArrayList<>();
        while (rows.hasNext()) {
            Row r = rows.next();
            list.add(new HistoryRow(
                    r.getString("message_id"),
                    r.getString("conversation_id"),
                    r.getString("sender_id"),
                    r.getString("recipient_id"),
                    r.getString("order_id"),
                    r.getString("payload_json"),
                    r.getOffsetDateTime("created_at").toInstant(),
                    r.getString("client_message_id")
            ));
        }
        return list;
    }
}
