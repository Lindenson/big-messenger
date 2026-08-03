package org.hormigas.ws.core.conversation.guard;

import org.hormigas.ws.domain.conversation.result.Access;
import org.hormigas.ws.domain.conversation.Conversation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ConversationAccess — the one membership/block check")
class ConversationAccessTest {

    private final ConversationAccess access = new ConversationAccess();

    private static Conversation conv(boolean clientBlocked, boolean masterBlocked) {
        return new Conversation("c1", "client", "master", Map.of(),
                clientBlocked, masterBlocked, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("null conversation → NOT_FOUND")
    void notFound() {
        assertEquals(Access.NOT_FOUND, access.check(null, "client"));
    }

    @Test
    @DisplayName("a non-participant → NOT_MEMBER")
    void notMember() {
        assertEquals(Access.NOT_MEMBER, access.check(conv(false, false), "stranger"));
    }

    @Test
    @DisplayName("a participant on a blocked pair → BLOCKED (either side's block counts)")
    void blocked() {
        assertEquals(Access.BLOCKED, access.check(conv(true, false), "client"));
        assertEquals(Access.BLOCKED, access.check(conv(false, true), "master"));
    }

    @Test
    @DisplayName("a participant on a live pair → OK")
    void ok() {
        assertEquals(Access.OK, access.check(conv(false, false), "client"));
        assertEquals(Access.OK, access.check(conv(false, false), "master"));
    }
}
