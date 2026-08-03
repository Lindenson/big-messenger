package hormiga.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WS — must-arrive to an OFFLINE recipient is held (dead_letter draft) and delivered on connect (ADR-014)")
class DeadLetterOfflineWsTest {

    static final Duration T = Duration.ofSeconds(10);

    @Test
    @DisplayName("notify while offline → the notice is held, then pushed as SYSTEM_OUT once the recipient connects")
    void heldWhileOfflineThenDeliveredOnConnect() throws Exception {
        String uid = UUID.randomUUID().toString();
        String cId = "dl-client-" + uid;

        // recipient is OFFLINE — emit the must-arrive notice now (it cannot be delivered live)
        Rest.Resp r = Rest.post("/api/system/notify",
                "{\"recipientId\":\"" + cId + "\",\"kind\":\"event\",\"body\":\"must-arrive-" + uid + "\"}",
                Rest.adminHdr("admin-" + uid, "ADMIN"));
        assertEquals(202, r.status(), "notify should be accepted while the recipient is offline");

        // now the recipient connects — the held notice must be redelivered by the poller
        try (WsTestClient client = WsTestClient.connect(Rest.WS_URL, Rest.hdr(cId, "CLIENT"))) {
            JsonNode out = client.awaitType("SYSTEM_OUT", T);
            assertNotNull(out, "a must-arrive notice held while offline must arrive on reconnect");
            assertEquals("must-arrive-" + uid, out.path("payload").path("body").asText());

            // ack it to retract the dead_letter draft (no-loss, no-duplicate)
            client.send(Msg.systemAck(cId, out.path("conversationId").asText(), "sysack-" + uid,
                    out.path("messageId").asText()));
            Thread.sleep(300);
        }
    }
}
