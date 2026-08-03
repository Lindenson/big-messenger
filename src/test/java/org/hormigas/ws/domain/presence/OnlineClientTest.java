package org.hormigas.ws.domain.presence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OnlineClient — identity invariant")
class OnlineClientTest {

    @Test
    @DisplayName("id is required")
    void idRequired() {
        assertThrows(NullPointerException.class, () -> new OnlineClient(null, "n", 1L));
    }

    @Test
    @DisplayName("a presence entry with an id is accepted")
    void validBuilds() {
        OnlineClient c = new OnlineClient("u1", "name", 42L);
        assertEquals("u1", c.id());
        assertEquals(42L, c.timestamp());
    }
}
