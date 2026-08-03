package org.hormigas.ws.domain.credentials;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClientData — identity invariant")
class ClientDataTest {

    @Test
    @DisplayName("id is required")
    void idRequired() {
        assertThrows(NullPointerException.class, () -> new ClientData(null, "n", "CLIENT", "e@x"));
    }

    @Test
    @DisplayName("role and email may be null (session-reconstructed presence)")
    void roleAndEmailNullable() {
        ClientData d = assertDoesNotThrow(() -> new ClientData("u1", "name", null, null));
        assertEquals("u1", d.id());
        assertNull(d.role());
        assertNull(d.email());
    }
}
