package org.hormigas.ws.domain.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.*;

import java.util.Map;

@Value
@Builder(toBuilder = true)
@NoArgsConstructor(force = true)
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = Message.MessageBuilder.class)
public class Message {

    /** Sanctioned metadata key: the opaque order reference (UC-H03). The messenger forwards all
     *  metadata uninterpreted, but extracts this one key to scope message-level freeze (UC-U22). */
    public static final String META_ORDER_ID = "orderId";

    @Builder.Default
    @With
    Long id = -1L;
    Long ackId;
    MessageType type;

    String senderId;
    String recipientId;
    String conversationId;

    String messageId;
    String correlationId;
    /**
     * The server-assigned canonical id of the acknowledged message, echoed to the sender on a CHAT_ACK so
     * the phone learns the authoritative id (it only ever generated its own). Lets the client address the
     * message by its server id afterwards (delete, read-receipt lastReadId, edits). Null on other frames.
     */
    String serverMessageId;

    long senderTimestamp;
    String senderTimezone;
    int creditsAvailable;

    long serverTimestamp;
    String sessionId;
    long sequenceNumber;

    Payload payload;
    Map<String, String> meta;

    @Value
    @Builder(toBuilder = true)
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonDeserialize(builder = Payload.PayloadBuilder.class)
    public static class Payload {
        String kind;
        String body;

        @JsonPOJOBuilder(withPrefix = "")
        public static class PayloadBuilder {
        }
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class MessageBuilder {
    }
}