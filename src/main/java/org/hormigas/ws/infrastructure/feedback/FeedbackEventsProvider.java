package org.hormigas.ws.infrastructure.feedback;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.hormigas.hl.contracts.feedback.events.IncomingHealthEvent;
import org.hormigas.hl.contracts.feedback.events.OutgoingHealthEvent;
import org.hormigas.hl.contracts.feedback.provider.InEventProvider;
import org.hormigas.hl.contracts.feedback.provider.OutEventProvider;

@ApplicationScoped
public class FeedbackEventsProvider implements InEventProvider<IncomingHealthEvent>, OutEventProvider<OutgoingHealthEvent> {

    @Inject
    Event<OutgoingHealthEvent> eventBusOutgoing;
    @Inject
    Event<IncomingHealthEvent> eventBusIncoming;

    public void fireOut(OutgoingHealthEvent event) {
        eventBusOutgoing.fire(event);
    }

    public void fireIn(IncomingHealthEvent event) {
        eventBusIncoming.fire(event);
    }
}
