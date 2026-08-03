package org.hormigas.ws.infrastructure.persistance.postgres.message;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.hormigas.ws.ports.message.ReadReceipts.Receipt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for {@link ReadReceiptsAdapter} (UC-U13) against real Postgres. Per-message status is
 * SENT → DELIVERED (READ is no longer a per-message status — read progress is a coalesced per-participant
 * receipt, see {@code ReadReceiptBatcher}). {@code markDelivered} is a guarded SENT→DELIVERED that never
 * downgrades a message already READ; {@code receipts} reads the status back oldest-first.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadReceiptsAdapterTest {

    @Inject PgPool client;
    @Inject ReadReceiptsAdapter adapter;

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
                    status VARCHAR(16) NOT NULL DEFAULT 'SENT'
                );
                """).execute().await().indefinitely();
        // Another @QuarkusTest may have created message_history first (without status) on the
        // shared Dev Services DB — ensure the column exists regardless of test order.
        client.query("ALTER TABLE message_history ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'SENT'")
                .execute().await().indefinitely();
    }

    @BeforeEach
    void cleanup() {
        client.query("TRUNCATE message_history RESTART IDENTITY").execute().await().indefinitely();
    }

    private void insert(String convId, String msgId, String sender, String recipient) {
        client.preparedQuery("""
                INSERT INTO message_history (message_id, conversation_id, sender_id, recipient_id, payload_json)
                VALUES ($1,$2,$3,$4,'{}'::jsonb)""")
                .execute(Tuple.of(msgId, convId, sender, recipient)).await().indefinitely();
    }

    @Test
    void markDelivered_moves_SENT_to_DELIVERED_and_never_downgrades_READ() {
        insert("conv", "m1", "master", "client"); // SENT
        assertEquals(1, adapter.markDelivered("m1").await().indefinitely());
        assertEquals("DELIVERED",
                adapter.receipts("conv").await().indefinitely().get(0).status());
        // already DELIVERED → no re-count
        assertEquals(0, adapter.markDelivered("m1").await().indefinitely());
        // once READ (advanced out-of-band by the receipt path), a late delivery mark must not downgrade it
        client.preparedQuery("UPDATE message_history SET status = 'READ' WHERE message_id = $1")
                .execute(Tuple.of("m1")).await().indefinitely();
        assertEquals(0, adapter.markDelivered("m1").await().indefinitely());
        assertEquals("READ",
                adapter.receipts("conv").await().indefinitely().get(0).status());
    }

    @Test
    void receipts_returns_all_messages_oldest_first() {
        insert("conv", "m1", "master", "client");
        insert("conv", "m2", "client", "master");
        List<Receipt> receipts = adapter.receipts("conv").await().indefinitely();
        assertEquals(2, receipts.size());
        assertEquals("m1", receipts.get(0).messageId());
        assertEquals("m2", receipts.get(1).messageId());
    }
}
