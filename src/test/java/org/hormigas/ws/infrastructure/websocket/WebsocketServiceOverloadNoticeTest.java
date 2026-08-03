package org.hormigas.ws.infrastructure.websocket;

import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("UC-H08 — ingress-reject (overload) notice shape")
class WebsocketServiceOverloadNoticeTest {

    @Test
    @DisplayName("overloadNotice is a SERVICE_OUT carrying the rejected messageId as correlationId")
    void overloadNoticeShape() {
        Message notice = WebsocketService.overloadNotice("rejected-msg-1");

        assertEquals(MessageType.SERVICE_OUT, notice.getType(), "reject notice is a SERVICE_OUT");
        assertEquals("rejected-msg-1", notice.getCorrelationId(),
                "correlationId = the rejected messageId so the sender can correlate and retry");
        assertEquals("event", notice.getPayload().getKind());
        assertEquals("overloaded", notice.getPayload().getBody());
    }
}
