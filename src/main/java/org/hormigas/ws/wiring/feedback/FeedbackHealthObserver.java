package org.hormigas.ws.wiring.feedback;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.hormigas.hl.contracts.feedback.events.OutgoingHealthEvent;
import org.hormigas.hl.feedback.FeedbackRegulator;

/**
 * The CDI seam that feeds outbound health signals into the (framework-neutral) {@link FeedbackRegulator}.
 * A {@code @ObservesAsync} observer must live on a bean, so this tiny bean is all that stays app-side —
 * the regulator itself is a plain POJO produced by {@link HlProducers}.
 */
@ApplicationScoped
public class FeedbackHealthObserver {

    @Inject
    FeedbackRegulator regulator;

    void onHealthEvent(@ObservesAsync OutgoingHealthEvent event) {
        regulator.observe(event.droppedDetected());
    }
}
