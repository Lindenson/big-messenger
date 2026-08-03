package org.hormigas.ws.ports.channel;

import io.smallrye.mutiny.Uni;
import org.hormigas.hl.contracts.stage.StageResult;

public interface DeliveryChannel<T> {
    Uni<StageResult<T>> deliver(T message);
}
