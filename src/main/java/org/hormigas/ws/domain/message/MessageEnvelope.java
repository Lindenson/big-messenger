package org.hormigas.ws.domain.message;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.hormigas.hl.contracts.backpressure.PublishOutcome;

@Builder
@Getter
@EqualsAndHashCode
public class MessageEnvelope<T> implements PublishOutcome {
    private final T message;
    private final boolean processed;
    // isProcessed() (Lombok @Getter on `processed`) satisfies PublishOutcome — the backpressure framework's
    // sole read off a sink result. This app→messenger-hl edge mirrors ClientSession → contracts.credits.Credits.
}
