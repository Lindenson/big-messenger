package org.hormigas.ws.infrastructure.persistance.postgres.message;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.hormigas.ws.ports.message.MessageModeration;

@Slf4j
@ApplicationScoped
public class MessageModerationAdapter implements MessageModeration {

    @Inject
    PgPool client;

    @Override
    public Uni<DeleteOutcome> deleteMessage(String conversationId, String callerId, String messageId) {
        // Path form: a single id of UNKNOWN type (from the URL). A message is addressable by EITHER its
        // server id (message_id, the history key) OR the client-original id (client_message_id) — for a
        // DELIVERED message the sender's phone only learned its own original id — so match on either.
        return resolveAndDelete(conversationId, callerId,
                "(message_id = $2 OR client_message_id = $2)", Tuple.of(conversationId, messageId));
    }

    @Override
    public Uni<DeleteOutcome> deleteMessageByIds(String conversationId, String callerId, String serverId, String clientId) {
        // Typed ids. Both → STRICT (message_id = serverId AND client_message_id = clientId), so a client id
        // that happens to collide with another row's server id can't select the wrong message. One → that
        // column only (the pre-ACK / post-ACK cases where the phone knows just one id).
        boolean hasServer = serverId != null && !serverId.isBlank();
        boolean hasClient = clientId != null && !clientId.isBlank();
        String where;
        Tuple params;
        if (hasServer && hasClient) {
            where = "message_id = $2 AND client_message_id = $3";
            params = Tuple.of(conversationId, serverId, clientId);
        } else if (hasServer) {
            where = "message_id = $2";
            params = Tuple.of(conversationId, serverId);
        } else {
            where = "client_message_id = $2";
            params = Tuple.of(conversationId, clientId);
        }
        return resolveAndDelete(conversationId, callerId, where, params);
    }

    /** Shared resolve → own-only authorize → not-frozen delete, over a caller-supplied match predicate. */
    private Uni<DeleteOutcome> resolveAndDelete(String conversationId, String callerId, String whereMatch, Tuple selectParams) {
        return client.preparedQuery(
                        "SELECT message_id, sender_id, frozen FROM message_history "
                                + "WHERE conversation_id = $1 AND " + whereMatch + " LIMIT 1")
                .execute(selectParams)
                .flatMap(rs -> {
                    var it = rs.iterator();
                    if (!it.hasNext()) {
                        return Uni.createFrom().item(DeleteOutcome.NOT_FOUND);
                    }
                    var row = it.next();
                    // Object-level authorization: a participant may hard-delete only their OWN messages,
                    // never the peer's. Membership alone (guardedRead) is not enough.
                    if (!callerId.equals(row.getString("sender_id"))) {
                        return Uni.createFrom().item(DeleteOutcome.FORBIDDEN);
                    }
                    if (row.getBoolean("frozen")) {
                        return Uni.createFrom().item(DeleteOutcome.FROZEN);
                    }
                    String canonicalId = row.getString("message_id");
                    return client.preparedQuery(
                                    "DELETE FROM message_history WHERE conversation_id = $1 AND message_id = $2 AND sender_id = $3 AND frozen = FALSE")
                            .execute(Tuple.of(conversationId, canonicalId, callerId))
                            .map(r -> r.rowCount() > 0 ? DeleteOutcome.DELETED : DeleteOutcome.FROZEN);
                });
    }

    @Override
    public Uni<Integer> freezeByOrder(String conversationId, String orderId) {
        return client.preparedQuery(
                        "UPDATE message_history SET frozen = TRUE WHERE conversation_id = $1 AND order_id = $2 AND frozen = FALSE")
                .execute(Tuple.of(conversationId, orderId))
                .map(r -> r.rowCount());
    }
}
