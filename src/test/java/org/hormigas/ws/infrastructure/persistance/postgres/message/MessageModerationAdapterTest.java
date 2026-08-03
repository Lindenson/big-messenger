package org.hormigas.ws.infrastructure.persistance.postgres.message;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.hormigas.ws.ports.message.MessageModeration.DeleteOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for {@link MessageModerationAdapter} (UC-U21/U22) against real Postgres.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageModerationAdapterTest {

    @Inject PgPool client;
    @Inject MessageModerationAdapter adapter;

    @BeforeAll
    void schema() {
        client.query("""
                CREATE TABLE IF NOT EXISTS message_history (
                    id BIGSERIAL PRIMARY KEY,
                    message_id VARCHAR(128) NOT NULL,
                    conversation_id VARCHAR(128) NOT NULL,
                    sender_id VARCHAR(128) NOT NULL,
                    recipient_id VARCHAR(128) NOT NULL,
                    payload_json JSONB NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT now() NOT NULL,
                    frozen BOOLEAN NOT NULL DEFAULT FALSE,
                    order_id VARCHAR(128)
                );
                """).execute().await().indefinitely();
        // Another @QuarkusTest may have created message_history first (without these columns) on
        // the shared Dev Services DB — ensure they exist regardless of test order.
        client.query("ALTER TABLE message_history ADD COLUMN IF NOT EXISTS frozen BOOLEAN NOT NULL DEFAULT FALSE")
                .execute().await().indefinitely();
        client.query("ALTER TABLE message_history ADD COLUMN IF NOT EXISTS order_id VARCHAR(128)")
                .execute().await().indefinitely();
        client.query("ALTER TABLE message_history ADD COLUMN IF NOT EXISTS client_message_id VARCHAR(128)")
                .execute().await().indefinitely();
    }

    @BeforeEach
    void cleanup() {
        client.query("TRUNCATE message_history RESTART IDENTITY").execute().await().indefinitely();
    }

    private void insert(String convId, String msgId, boolean frozen) {
        insert(convId, msgId, frozen, null);
    }

    private void insert(String convId, String msgId, boolean frozen, String orderId) {
        client.preparedQuery("""
                INSERT INTO message_history (message_id, conversation_id, sender_id, recipient_id, payload_json, frozen, order_id)
                VALUES ($1,$2,'s','r','{}'::jsonb,$3,$4)""")
                .execute(Tuple.of(msgId, convId, frozen, orderId)).await().indefinitely();
    }

    private void insertWithClientId(String convId, String msgId, String clientMsgId) {
        client.preparedQuery("""
                INSERT INTO message_history (message_id, conversation_id, sender_id, recipient_id, payload_json, frozen, client_message_id)
                VALUES ($1,$2,'s','r','{}'::jsonb,false,$3)""")
                .execute(Tuple.of(msgId, convId, clientMsgId)).await().indefinitely();
    }

    @Test
    void delete_absent_is_not_found() {
        assertEquals(DeleteOutcome.NOT_FOUND, adapter.deleteMessage("conv", "s", "missing").await().indefinitely());
    }

    @Test
    void delete_non_frozen_by_the_sender_succeeds() {
        insert("conv", "m1", false); // sender_id = 's'
        assertEquals(DeleteOutcome.DELETED, adapter.deleteMessage("conv", "s", "m1").await().indefinitely());
        assertEquals(DeleteOutcome.NOT_FOUND, adapter.deleteMessage("conv", "s", "m1").await().indefinitely());
    }

    @Test
    void delete_by_a_non_sender_is_forbidden_and_leaves_the_message() {
        insert("conv", "m1", false); // sender_id = 's'
        // object-level authz: a participant may delete only their OWN messages, never the peer's
        assertEquals(DeleteOutcome.FORBIDDEN, adapter.deleteMessage("conv", "other", "m1").await().indefinitely());
        // the message survives — the rightful owner can still delete it
        assertEquals(DeleteOutcome.DELETED, adapter.deleteMessage("conv", "s", "m1").await().indefinitely());
    }

    @Test
    void delete_by_the_client_original_id_succeeds() {
        // the phone knows only the id it generated (client_message_id); the server id differs
        insertWithClientId("conv", "server-ulid-1", "phone-uid-1");
        // delete addressing the message by its CLIENT-original id resolves to the same row
        assertEquals(DeleteOutcome.DELETED, adapter.deleteMessage("conv", "s", "phone-uid-1").await().indefinitely());
        // and it's gone (neither id resolves anymore)
        assertEquals(DeleteOutcome.NOT_FOUND, adapter.deleteMessage("conv", "s", "server-ulid-1").await().indefinitely());
    }

    @Test
    void delete_by_the_server_id_still_works_when_a_client_id_is_present() {
        insertWithClientId("conv", "server-ulid-2", "phone-uid-2");
        // the authoritative server id also resolves the row (either id addresses the message)
        assertEquals(DeleteOutcome.DELETED, adapter.deleteMessage("conv", "s", "server-ulid-2").await().indefinitely());
    }

    @Test
    void delete_by_client_id_is_still_own_only() {
        insertWithClientId("conv", "server-ulid-3", "phone-uid-3");
        // ownership still applies when addressing by the client-original id
        assertEquals(DeleteOutcome.FORBIDDEN, adapter.deleteMessage("conv", "other", "phone-uid-3").await().indefinitely());
    }

    @Test
    void delete_frozen_is_rejected() {
        insert("conv", "m1", true);
        assertEquals(DeleteOutcome.FROZEN, adapter.deleteMessage("conv", "s", "m1").await().indefinitely());
    }

    // ── typed delete (deleteMessageByIds): both = strict match of both, one = by that id ──

    @Test
    void deleteByIds_bothIds_strictMatch_deletes() {
        insertWithClientId("conv", "server-ulid-b", "phone-uid-b");
        assertEquals(DeleteOutcome.DELETED,
                adapter.deleteMessageByIds("conv", "s", "server-ulid-b", "phone-uid-b").await().indefinitely());
    }

    @Test
    void deleteByIds_bothIds_mismatchedPair_isNotFound_andDeletesNothing() {
        // two distinct messages; a strict (serverA AND clientB) pairing matches NEITHER row → no accidental
        // cross-column delete (the win of strict-both over the loose OR).
        insertWithClientId("conv", "server-A", "phone-A");
        insertWithClientId("conv", "server-B", "phone-B");
        assertEquals(DeleteOutcome.NOT_FOUND,
                adapter.deleteMessageByIds("conv", "s", "server-A", "phone-B").await().indefinitely());
        assertEquals(DeleteOutcome.DELETED, adapter.deleteMessage("conv", "s", "server-A").await().indefinitely());
        assertEquals(DeleteOutcome.DELETED, adapter.deleteMessage("conv", "s", "server-B").await().indefinitely());
    }

    @Test
    void deleteByIds_serverIdOnly_deletes() {
        insertWithClientId("conv", "server-only", "phone-x");
        assertEquals(DeleteOutcome.DELETED,
                adapter.deleteMessageByIds("conv", "s", "server-only", null).await().indefinitely());
    }

    @Test
    void deleteByIds_clientIdOnly_deletes_theBeforeAckCase() {
        // before the ACK the phone knows only its own generated id
        insertWithClientId("conv", "server-y", "phone-only");
        assertEquals(DeleteOutcome.DELETED,
                adapter.deleteMessageByIds("conv", "s", null, "phone-only").await().indefinitely());
    }

    @Test
    void deleteByIds_isStillOwnOnly() {
        insertWithClientId("conv", "server-z", "phone-z");
        assertEquals(DeleteOutcome.FORBIDDEN,
                adapter.deleteMessageByIds("conv", "other", "server-z", "phone-z").await().indefinitely());
    }

    @Test
    void freezeByOrder_freezes_only_that_order_and_blocks_its_delete() {
        // one conversation, two orders — a contract on order-1 must NOT freeze order-2's messages
        insert("conv", "m1", false, "order-1");
        insert("conv", "m2", false, "order-1");
        insert("conv", "m3", false, "order-2");
        Integer n = adapter.freezeByOrder("conv", "order-1").await().indefinitely();
        assertEquals(2, n, "only the two order-1 messages are frozen");
        // order-1 messages are now immutable
        assertEquals(DeleteOutcome.FROZEN, adapter.deleteMessage("conv", "s", "m1").await().indefinitely());
        // order-2 message in the SAME chat stays deletable (no chat-level freeze)
        assertEquals(DeleteOutcome.DELETED, adapter.deleteMessage("conv", "s", "m3").await().indefinitely());
    }

    @Test
    void freezeByOrder_is_idempotent_and_scoped() {
        insert("conv", "m1", false, "order-1");
        assertEquals(1, adapter.freezeByOrder("conv", "order-1").await().indefinitely());
        // re-freeze marks nothing new; an unknown order freezes nothing
        assertEquals(0, adapter.freezeByOrder("conv", "order-1").await().indefinitely());
        assertEquals(0, adapter.freezeByOrder("conv", "order-x").await().indefinitely());
    }
}
