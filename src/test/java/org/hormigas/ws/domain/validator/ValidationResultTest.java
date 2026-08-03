package org.hormigas.ws.domain.validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationResult — validity and defensive copy")
class ValidationResultTest {

    @Test
    @DisplayName("no errors (null or empty) is valid")
    void validWhenNoErrors() {
        assertTrue(new ValidationResult(null).isValid());
        assertTrue(new ValidationResult(List.of()).isValid());
    }

    @Test
    @DisplayName("any error makes it invalid")
    void invalidWhenErrors() {
        assertFalse(new ValidationResult(List.of("bad type")).isValid());
    }

    @Test
    @DisplayName("of() takes a defensive copy — later mutation of the source does not leak in")
    void ofCopiesDefensively() {
        List<String> src = new java.util.ArrayList<>(List.of("e1"));
        ValidationResult r = ValidationResult.of(src);
        src.add("e2");
        assertEquals(1, r.errors().size());
    }
}
