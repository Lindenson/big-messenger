package hormiga.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WS — an attachment, once confirmed, is delivered to the recipient as a chat message (ADR-010)")
@Tag("smoke")
class AttachmentWsTest {

    static final Duration T = Duration.ofSeconds(8);
    String uid, cId, mId, conv;

    @BeforeEach
    void setup() {
        uid = UUID.randomUUID().toString();
        cId = "att-client-" + uid;
        mId = "att-master-" + uid;
        conv = Rest.createChat(cId, mId);
    }

    @Test
    @DisplayName("upload-url → PUT bytes → confirm → the online recipient receives a CHAT_OUT (kind=attachment)")
    void confirmedAttachmentIsDeliveredAsChatMessage() throws Exception {
        try (WsTestClient client = WsTestClient.connect(Rest.WS_URL, Rest.hdr(cId, "CLIENT"))) {
            Thread.sleep(1000); // let the recipient register

            // phase 1 — presigned upload URL (master is the uploader)
            Rest.Resp up = Rest.post(
                    "/api/chats/" + conv + "/attachments/upload-url",
                    "{\"fileName\":\"pic.txt\",\"contentType\":\"text/plain\",\"sizeBytes\":5}",
                    Rest.hdr(mId, "MASTER"));
            assertEquals(201, up.status(), "upload-url should be created");
            String attId = up.body().get("attachmentId").asText();
            String uploadUrl = up.body().get("uploadUrl").asText();

            // phase 2 — bytes go straight to storage
            assertEquals(200, Rest.put(uploadUrl, "bytes"), "presigned PUT to storage should succeed");

            // phase 3 — confirm emits the attachment into the SAME delivery pipeline as a chat message
            Rest.Resp confirm = Rest.post(
                    "/api/chats/" + conv + "/attachments/" + attId + "/confirm", null, Rest.hdr(mId, "MASTER"));
            assertEquals(202, confirm.status(), "confirm should publish the message");

            // the recipient receives it as a normal delivered CHAT_OUT, marked as an attachment
            JsonNode out = client.awaitType("CHAT_OUT", T);
            assertNotNull(out, "recipient should receive the attachment as a CHAT_OUT");
            assertEquals("attachment", out.path("payload").path("kind").asText(), "payload kind = attachment");
            assertEquals(attId, out.path("meta").path("attachmentId").asText(), "meta carries the attachmentId");
            assertEquals(cId, out.path("recipientId").asText());
        }
    }
}
