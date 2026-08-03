package load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * STEADY inbound-capacity harness: a fixed set of talkers each hold ONE WS socket for the whole run and
 * send CHAT_IN at a constant pace — no waves, no talker rotation, no online pool, no history-sync. This
 * gives a constant offered rate so the server's steady-state persist capacity is just {@code μ} (published
 * rate) once the queue is saturated — unlike the bursty wave sim, whose point-measurements are noisy.
 *
 * Recipients are offline (no pool), so every CHAT_IN exercises the persist path (outbox), which is what we
 * are measuring; live delivery is out of scope here.
 *
 * <pre>
 *   mvn gatling:test -Dgatling.simulationClass=load.MessengerSteadySimulation \
 *     -Dload.baseUrl=http://hormiga-messenger-app:8080 -Dload.wsUrl=ws://hormiga-messenger-app:8080 \
 *     -Dload.adminKey=... -Dload.talkers=40 -Dload.ratePerMsgMs=10 -Dload.durationSec=120
 * </pre>
 */
public class MessengerSteadySimulation extends Simulation {

    private static final int TALKERS = Integer.getInteger("load.talkers", 40);
    private static final int RATE_MS = Integer.getInteger("load.ratePerMsgMs", 10); // per-talker: 1000/RATE_MS msg/s
    private static final int DURATION = Integer.getInteger("load.durationSec", 120);
    private static final int RAMP = Integer.getInteger("load.rampSec", 10);
    private static final String BASE = System.getProperty("load.baseUrl", "http://localhost:8080");
    private static final String WS_BASE = System.getProperty("load.wsUrl", "ws://localhost:8080");
    private static final String ADMIN_KEY = System.getProperty("load.adminKey", "");

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE).wsBaseUrl(WS_BASE)
            .contentTypeHeader("application/json").acceptHeader("application/json");

    private static final String[] BODIES = {
            "Здравствуйте! Когда удобно?", "Цена окончательная?", "Могу подъехать завтра",
            "Отправил фото, посмотрите", "Спасибо, договорились", "А гарантия есть?"
    };

    private static final Iterator<Map<String, Object>> msgFeeder = java.util.stream.Stream.generate(() -> {
        Map<String, Object> m = new HashMap<>();
        m.put("body", BODIES[ThreadLocalRandom.current().nextInt(BODIES.length)]);
        m.put("mid", UUID.randomUUID().toString());
        m.put("now", System.currentTimeMillis());
        return m;
    }).iterator();

    private static final String CHAT_IN =
            "{\"type\":\"CHAT_IN\",\"senderId\":\"#{mId}\",\"recipientId\":\"#{peer}\","
                    + "\"conversationId\":\"#{conv}\",\"messageId\":\"#{mid}\",\"senderTimestamp\":#{now},"
                    + "\"senderTimezone\":\"UTC\",\"payload\":{\"kind\":\"text\",\"body\":\"#{body}\"}}";

    private final ScenarioBuilder talker = scenario("steady-talker")
            .exec(session -> session
                    .set("mId", "t-" + UUID.randomUUID())
                    .set("peer", "u-" + ThreadLocalRandom.current().nextInt(100000))) // offline recipient
            .exec(http("steady-create-chat").post("/api/chats")
                    .header("X-Admin-Key", ADMIN_KEY)
                    .header("X-User-Id", "load-svc").header("X-User", "Load Service")
                    .header("X-Role", "SERVICE").header("X-User-Email", "svc@load")
                    .body(StringBody("{\"clientId\":\"#{peer}\",\"masterId\":\"#{mId}\",\"metadata\":{\"orderId\":\"#{mId}\"}}"))
                    .check(status().in(200, 201), jsonPath("$.id").saveAs("conv")))
            .doIf(session -> session.contains("conv")).then(
                    exec(ws("steady-connect").wsName("m").connect("/ws")
                            .header("X-User-Id", "#{mId}").header("X-User", "M")
                            .header("X-Role", "MASTER").header("X-User-Email", "#{mId}@load"))
                            .during(Duration.ofSeconds(DURATION)).on(
                                    feed(msgFeeder)
                                            .exec(ws("steady-send").wsName("m").sendText(CHAT_IN))
                                            .pace(Duration.ofMillis(RATE_MS)))
                            .exec(ws("steady-close").wsName("m").close()));

    {
        setUp(talker.injectClosed(
                rampConcurrentUsers(0).to(TALKERS).during(Duration.ofSeconds(RAMP)),
                constantConcurrentUsers(TALKERS).during(Duration.ofSeconds(DURATION))))
                .protocols(httpProtocol).maxDuration(Duration.ofSeconds(DURATION + RAMP + 30));
    }
}
