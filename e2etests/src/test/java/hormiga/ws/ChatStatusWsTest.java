package hormiga.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WS — status machine SENT→DELIVERED(ack); read tracked via peerLastReadId (UC-U13/U14)")
class ChatStatusWsTest {

    static final Duration T = Duration.ofSeconds(8);
    String uid, cId, mId, conv;

    @BeforeEach
    void setup() {
        uid = UUID.randomUUID().toString();
        cId = "client-" + uid;
        mId = "master-" + uid;
        conv = Rest.createChat(cId, mId);
    }

    @Test
    @DisplayName("recipient CHAT_ACK drives SENT→DELIVERED; READ_IN advances the peer read watermark (peerLastReadId)")
    void deliveredThenRead() throws Exception {
        try (WsTestClient master = WsTestClient.connect(Rest.WS_URL, Rest.hdr(mId, "MASTER"));
             WsTestClient client = WsTestClient.connect(Rest.WS_URL, Rest.hdr(cId, "CLIENT"))) {
            Thread.sleep(1200);
            master.send(Msg.chatIn(mId, cId, conv, uid, "read-" + uid, null));

            JsonNode out = client.awaitType("CHAT_OUT", T);
            assertNotNull(out, "client should receive CHAT_OUT");
            String serverId = out.path("messageId").asText();   // server-assigned id (history key)
            long outboxId = out.path("id").asLong();

            // the sender's CHAT_ACK echoes the server-assigned id (serverMessageId) — the phone only ever
            // generated its own id, so this is how it learns the authoritative one (delete / read-receipts).
            JsonNode ack = master.awaitType("CHAT_ACK", T);
            assertNotNull(ack, "sender should receive a CHAT_ACK");
            assertEquals(serverId, ack.path("serverMessageId").asText(), "ACK carries the server-assigned id");

            // before any ACK the persisted status is SENT (DELIVERED is ACK-driven)
            assertTrue(hasStatus(receipts(), "SENT"), "status should start SENT");

            // recipient delivery-ACK → DELIVERED (correlationId = delivered messageId)
            client.send(Msg.chatAck(cId, mId, conv, "ack-" + uid, serverId, outboxId));
            Thread.sleep(1200);
            assertTrue(hasStatus(receipts(), "DELIVERED"), "status should become DELIVERED after ACK");

            // recipient reads up to the delivered message over WS (READ_IN.correlationId = last-read server id).
            // READ is no longer a per-message status — the read watermark lives on the conversation as
            // peerLastReadId (✓✓ ⇔ a sent message's id <= peerLastReadId), coalesced + write-behind.
            client.send(Msg.readIn(cId, mId, conv, "r-" + uid, serverId));
            Thread.sleep(1200);
            JsonNode senderView = Rest.get("/api/chats/" + conv + "/messages", Rest.hdr(mId, "MASTER")).body();
            assertEquals(serverId, senderView.path("peerLastReadId").asText(),
                    "sender should see the recipient's read watermark as peerLastReadId");
        }
    }

    private JsonNode receipts() {
        return Rest.get("/api/chats/" + conv + "/receipts", Rest.hdr(cId, "CLIENT")).body();
    }

    private static boolean hasStatus(JsonNode receipts, String status) {
        if (receipts == null || !receipts.isArray()) return false;
        for (JsonNode r : receipts) {
            if (status.equals(r.path("status").asText())) return true;
        }
        return false;
    }
}
