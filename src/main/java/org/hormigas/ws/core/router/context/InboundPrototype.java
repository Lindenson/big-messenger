package org.hormigas.ws.core.router.context;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.message.MessageType;
import org.hormigas.ws.domain.generator.IdGenerator;

import static org.hormigas.ws.core.router.PipelineResolver.PipelineType;
import static org.hormigas.ws.domain.message.MessageType.*;


@ApplicationScoped
@RequiredArgsConstructor
public class InboundPrototype {

    private final IdGenerator idGenerator;

    public RouterContext<Message> createOutboundContext(PipelineType pipeline, Message message) {

        return RouterContext.<Message>builder()
                .pipelineType(pipeline)
                .payload(message.toBuilder()
                        .messageId(idGenerator.generateId())
                        .ackId(message.getAckId())
                        .type(switchMesssageType(message))
                        // ACK-like frames carry a client-supplied reference in correlationId that must survive:
                        // CHAT_ACK/SYSTEM_ACK (the acked id) and READ_IN (the last-read id / receipt watermark).
                        // Everything else gets correlationId = its own (client) messageId, so the delivered copy
                        // still points back at what the sender sent.
                        .correlationId(switch (message.getType()) {
                            case CHAT_ACK, SYSTEM_ACK, READ_IN -> message.getCorrelationId();
                            default -> message.getMessageId();
                        })
                        .serverTimestamp(System.currentTimeMillis())
                        .build())
                .build();
    }

    private MessageType switchMesssageType(Message message) {
        return switch (message.getType()) {
            case CHAT_IN -> CHAT_OUT;
            case SIGNAL_IN -> SIGNAL_OUT;
            case TYPING_IN -> TYPING_OUT;
            default -> message.getType();
        };
    }
}
