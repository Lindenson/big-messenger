package org.hormigas.ws.wiring.feedback;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.hl.feedback.FeedbackRegulator;

/**
 * CDI wiring for the feedback loop. Sits with its {@link FeedbackHealthObserver}: together they are the whole
 * app-side glue for the adaptive regulator — the producer builds the pure POJO from config, the observer
 * feeds it health events. The regulator itself lives in {@code messenger-hl}.
 */
@ApplicationScoped
public class FeedbackProducers {

    /**
     * The adaptive poll-interval regulator, configured from {@link MessengerConfig}. Produced as the concrete
     * {@link FeedbackRegulator} (which is-a {@code Regulator}) so {@link FeedbackHealthObserver} can inject it
     * and feed it {@code observe(...)}.
     */
    @Produces
    @ApplicationScoped
    FeedbackRegulator regulator(MessengerConfig config) {
        return FeedbackRegulator.builder()
                .baseIntervalMs(config.feedback().additionalMs())
                .maxIntervalMs(config.feedback().maxMs())
                .growthFactor(config.feedback().adjustmentFactor())
                .decayFactor(config.feedback().recoveryFactor())
                .build();
    }
}
