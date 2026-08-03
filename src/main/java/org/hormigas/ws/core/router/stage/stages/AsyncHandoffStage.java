package org.hormigas.ws.core.router.stage.stages;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.hormigas.ws.core.router.context.RouterContext;
import org.hormigas.ws.core.router.pipeline.HandoffSubPipeline;
import org.hormigas.ws.core.router.stage.PipelineStage;
import org.hormigas.ws.domain.message.Message;

/**
 * Technical hand-off stage placed after the ack in the persistent inbound branch. It is a thin pipeline
 * participant: it delegates to the {@link HandoffSubPipeline}, which owns the decoupling machinery (the
 * fast-delivery publisher + its delivery continuation). The stage itself knows nothing about the publisher
 * or the downstream delivery — everything after the hand-off runs on the sub-pipeline's own subscription,
 * off the inbound persist path.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class AsyncHandoffStage implements PipelineStage<RouterContext<Message>> {

    private final HandoffSubPipeline handoffSubPipeline;

    @Override
    public Uni<RouterContext<Message>> apply(RouterContext<Message> ctx) {
        return handoffSubPipeline.handoff(ctx);
    }
}
