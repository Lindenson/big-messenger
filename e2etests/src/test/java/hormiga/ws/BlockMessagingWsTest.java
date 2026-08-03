package hormiga.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WS — block is the messaging stop: a blocked pair cannot deliver; unblock resumes (UC-H07)")
class BlockMessagingWsTest {

    static final Duration T = Duration.ofSeconds(6);
    static final Duration QUIET = Duration.ofSeconds(3); // long enough to be sure nothing arrives
    String uid, cId, mId, conv;

    @BeforeEach
    void setup() {
        uid = UUID.randomUUID().toString();
        cId = "blk-client-" + uid;
        mId = "blk-master-" + uid;
        conv = Rest.createChat(cId, mId);
    }

    @Test
    @DisplayName("while blocked the recipient receives nothing; after unblock the message is delivered")
    void blockStopsDeliveryUnblockResumes() throws Exception {
        // client blocks the peer (mutual — disables messaging for the pair)
        assertEquals(204, Rest.post("/api/chats/" + conv + "/block", null, Rest.hdr(cId, "CLIENT")).status());

        try (WsTestClient master = WsTestClient.connect(Rest.WS_URL, Rest.hdr(mId, "MASTER"));
             WsTestClient client = WsTestClient.connect(Rest.WS_URL, Rest.hdr(cId, "CLIENT"))) {
            Thread.sleep(1200);

            // master tries to send into the blocked chat — nothing should reach the client
            master.send(Msg.chatIn(mId, cId, conv, uid, "blocked-" + uid, null));
            assertNull(client.awaitType("CHAT_OUT", QUIET), "a blocked pair must not deliver");

            // unblock → messaging resumes
            assertEquals(204, Rest.delete("/api/chats/" + conv + "/block", Rest.hdr(cId, "CLIENT")).status());
            Thread.sleep(500); // let the block-cache invalidation settle
            String body = "after-unblock-" + uid;
            master.send(Msg.chatIn(mId, cId, conv, UUID.randomUUID().toString(), body, null));

            JsonNode out = client.awaitType("CHAT_OUT", T);
            assertNotNull(out, "after unblock the message should be delivered");
            assertEquals(body, out.path("payload").path("body").asText());
        }
    }
}
