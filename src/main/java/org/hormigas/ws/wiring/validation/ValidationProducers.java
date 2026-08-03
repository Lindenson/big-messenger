package org.hormigas.ws.wiring.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.domain.validator.MessageValidator;
import org.hormigas.ws.domain.validator.Validator;

/**
 * CDI wiring for the domain validators. The validators are framework-neutral pure POJOs (the domain owns
 * the rules); this producer places them into the context, so the domain stays free of CDI annotations —
 * the same {@code @Produces}-at-the-edge pattern used by {@code wiring.highload.HighloadProducers}.
 */
@ApplicationScoped
public class ValidationProducers {

    /** The inbound {@link Message} validator — a pure domain POJO, exposed here as an application-scoped bean. */
    @Produces
    @ApplicationScoped
    Validator<Message> messageValidator() {
        return new MessageValidator();
    }
}
