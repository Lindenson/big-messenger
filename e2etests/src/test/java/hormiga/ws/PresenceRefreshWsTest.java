package hormiga.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis self-heal for presence (option A). A connected client is in {@code /api/presence}; after a full
 * Redis wipe the view goes empty; the per-instance presence-refresh sweep re-asserts the still-live local
 * session, so the client reappears without reconnecting. Delivery/GC never read Redis presence, so a wipe
 * only affects this view.
 *
 * <p>Slow (waits for a refresh tick) and needs {@code docker exec} on the Redis container, so it is tagged
 * {@code presence} and excluded from the default/smoke run. Run the app with a short interval to keep it
 * quick: {@code -Dprocessing.session.presence-refresh-every=3s}.
 */
@DisplayName("WS — presence self-heals from local sessions after a Redis wipe (no reconnect needed)")
@Tag("presence")
class PresenceRefreshWsTest {

    static final String REDIS = System.getProperty("e2e.redisContainer", "e2etests-messenger-redis-1");

    @Test
    @DisplayName("connected client → FLUSHALL Redis → the refresh sweep re-adds it to /api/presence")
    void presenceRebuiltAfterWipe() throws Exception {
        String uid = UUID.randomUUID().toString();
        String cId = "pres-" + uid;

        try (WsTestClient client = WsTestClient.connect(Rest.WS_URL, Rest.hdr(cId, "CLIENT"))) {
            Thread.sleep(1200); // register + initial presence.add

            assertTrue(presenceContains(cId), "the connected client should be listed in /api/presence");

            // simulate an extraordinary Redis loss — wipe everything
            assertEquals(0, flushRedis(), "docker exec redis FLUSHALL should succeed");
            Thread.sleep(500);
            assertFalse(presenceContains(cId), "right after FLUSHALL the presence view is empty");

            // the periodic presence-refresh re-asserts this instance's live sessions — no client reconnect
            assertTrue(awaitPresenceContains(cId, Duration.ofSeconds(40)),
                    "the refresh sweep must rebuild presence from the still-open local session");
        }
    }

    private boolean presenceContains(String id) {
        Rest.Resp r = Rest.get("/api/presence", Rest.hdr("probe-" + id, "CLIENT"));
        if (r.status() != 200 || r.body() == null || !r.body().isArray()) return false;
        for (JsonNode n : r.body()) {
            if (id.equals(n.path("id").asText())) return true;
        }
        return false;
    }

    private boolean awaitPresenceContains(String id, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (presenceContains(id)) return true;
            Thread.sleep(1000);
        }
        return false;
    }

    private int flushRedis() throws Exception {
        Process p = new ProcessBuilder("docker", "exec", REDIS, "redis-cli", "FLUSHALL")
                .redirectErrorStream(true).start();
        return p.waitFor();
    }
}
