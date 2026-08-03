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
import java.util.concurrent.atomic.AtomicInteger;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * STEADY end-to-end harness — exercises BOTH directions at a constant rate so both queues (inbound persist +
 * outbound fast-delivery) can be watched filling/draining. Unlike the wave sim it has no bursts and no
 * reconnect storm: online receivers connect once, stay, and prompt-ACK every CHAT_OUT; talkers hold a socket
 * and send CHAT_IN to a random ONLINE peer at a constant pace. Realistic: every delivered message produces a
 * CHAT_ACK, so inbound routeIn carries CHAT_IN + CHAT_ACK while outbound carries CHAT_OUT.
 *
 * <pre>
 *   mvn gatling:test -Dgatling.simulationClass=load.MessengerEndToEndSimulation \
 *     -Dload.baseUrl=http://hormiga-messenger-app:8080 -Dload.wsUrl=ws://hormiga-messenger-app:8080 \
 *     -Dload.adminKey=... -Dload.online=200 -Dload.talkers=30 -Dload.ratePerMsgMs=10 -Dload.durationSec=120
 * </pre>
 */
public class MessengerEndToEndSimulation extends Simulation {

    private static final int ONLINE = Integer.getInteger("load.online", 200);     // online receivers (targets)
    private static final int TALKERS = Integer.getInteger("load.talkers", 30);    // concurrent senders
    private static final int RATE_MS = Integer.getInteger("load.ratePerMsgMs", 10); // per-talker: 1000/RATE_MS msg/s
    private static final int DURATION = Integer.getInteger("load.durationSec", 120);
    private static final int RAMP = Integer.getInteger("load.rampSec", 15);
    private static final int ACK_BATCH = Integer.getInteger("load.ackBatch", 5000); // ACKs per online session before reconnect (C churn)
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

    // Online receiver ids, handed out sequentially but BOUND to [0,ONLINE) via `% ONLINE`. Bounding is the
    // fix for the delivered→0 collapse: with an unbounded counter, every replacement VU spawned by
    // injectClosed's constantConcurrentUsers got an ever-larger id (u-ONLINE, u-ONLINE+1, …) that drifted
    // OUT of the talkers' [0,ONLINE) target range, so after ~1 min every CHAT_OUT targeted a receiver id that
    // had left → the server found no session → silent skip → not_delivered flood, delivered=0. Mod-bounding
    // keeps recycled ids inside the target range (single-active-session eviction handles any transient
    // collision), so talkers always address a live receiver.
    private static final AtomicInteger poolSeq = new AtomicInteger();
    private static final Iterator<Map<String, Object>> poolFeeder = java.util.stream.Stream.generate(() -> {
        Map<String, Object> m = new HashMap<>();
        m.put("poolId", "u-" + (poolSeq.getAndIncrement() % ONLINE));
        return m;
    }).iterator();

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

    // ackId echoes the delivered CHAT_OUT's numeric `id` (the Tetris watermark key); correlationId echoes
    // its messageId (correlation). Both are required by the server for a valid CHAT_ACK.
    private static final String CHAT_ACK =
            "{\"type\":\"CHAT_ACK\",\"senderId\":\"#{poolId}\",\"recipientId\":\"#{rsender}\","
                    + "\"conversationId\":\"#{rconv}\",\"messageId\":\"#{ackMid}\",\"correlationId\":\"#{rmid}\","
                    + "\"ackId\":#{rackid},\"senderTimestamp\":#{ackTs},\"senderTimezone\":\"UTC\"}";

    // Online receiver — stays online for the WHOLE run and prompt-ACKs each CHAT_OUT. The ACK send doubles as
    // the trigger that awaits the next delivery (proven wave-sim pattern), so per-recipient pending stays ≈ 0.
    //
    // The scenario is an OUTER during(DURATION) reconnect loop (mirrors the wave sim). This is the second half
    // of the delivered→0 fix: previously the VU ran ONE session and then FINISHED — injectClosed replaced the
    // finished VU with a brand-new (drifting) id, so the live-receiver id set walked out of the talkers' target
    // range. Wrapping the body in an outer loop keeps the SAME VU (hence the SAME id) online for the whole run;
    // an await timeout just exits the inner ack loop → close → reconnect with the same id.
    private final ScenarioBuilder receiver = scenario("online-receiver")
            .feed(poolFeeder)
            .during(Duration.ofSeconds(DURATION)).on(
                    exec(ws("recv-connect").wsName("c").connect("/ws")
                            .header("X-User-Id", "#{poolId}").header("X-User", "C")
                            .header("X-Role", "CLIENT").header("X-User-Email", "#{poolId}@load")
                            .await(10).on(ws.checkTextMessage("await-first")
                                    .matching(jsonPath("$.type").is("CHAT_OUT"))
                                    .check(jsonPath("$.messageId").saveAs("rmid"),
                                            jsonPath("$.senderId").saveAs("rsender"),
                                            jsonPath("$.conversationId").saveAs("rconv"),
                                            jsonPath("$.id").saveAs("rackid"))))
                            // prompt-ACK loop: ack the message just received, then await+capture the next. The
                            // await(5) paces the loop (a dead/idle socket blocks up to 5s, so no KO spin) and a
                            // timeout simply ends the batch → reconnect. ACK_BATCH is large so a busy receiver
                            // rarely cycles; an idle one exits via the await timeout and reconnects promptly.
                            .doIf(session -> session.contains("rmid")).then(
                                    repeat(ACK_BATCH).on(
                                            exec(session -> session.set("ackMid", "ack-" + UUID.randomUUID())
                                                    .set("ackTs", System.currentTimeMillis()))
                                                    .exec(ws("recv-ack").wsName("c").sendText(CHAT_ACK)
                                                            .await(5).on(ws.checkTextMessage("await-next")
                                                                    .matching(jsonPath("$.type").is("CHAT_OUT"))
                                                                    .check(jsonPath("$.messageId").saveAs("rmid"),
                                                                            jsonPath("$.senderId").saveAs("rsender"),
                                                                            jsonPath("$.conversationId").saveAs("rconv"),
                                                                            jsonPath("$.id").saveAs("rackid"))))))
                            .exec(ws("recv-close").wsName("c").close())
                            .exec(session -> session.remove("rmid")));

    // Steady talker: create a chat with a random ONLINE peer, connect, send CHAT_IN at a constant pace.
    private final ScenarioBuilder talker = scenario("steady-talker")
            .exec(session -> session
                    .set("mId", "t-" + UUID.randomUUID())
                    .set("peer", "u-" + ThreadLocalRandom.current().nextInt(ONLINE)))
            .exec(http("e2e-create-chat").post("/api/chats")
                    .header("X-Admin-Key", ADMIN_KEY)
                    .header("X-User-Id", "load-svc").header("X-User", "Load Service")
                    .header("X-Role", "SERVICE").header("X-User-Email", "svc@load")
                    .body(StringBody("{\"clientId\":\"#{peer}\",\"masterId\":\"#{mId}\",\"metadata\":{\"orderId\":\"#{mId}\"}}"))
                    .check(status().in(200, 201), jsonPath("$.id").saveAs("conv")))
            .doIf(session -> session.contains("conv")).then(
                    exec(ws("e2e-connect").wsName("m").connect("/ws")
                            .header("X-User-Id", "#{mId}").header("X-User", "M")
                            .header("X-Role", "MASTER").header("X-User-Email", "#{mId}@load"))
                            .during(Duration.ofSeconds(DURATION)).on(
                                    feed(msgFeeder)
                                            .exec(ws("e2e-send").wsName("m").sendText(CHAT_IN))
                                            .pace(Duration.ofMillis(RATE_MS)))
                            .exec(ws("e2e-close").wsName("m").close()));

    {
        setUp(
                receiver.injectClosed(
                        rampConcurrentUsers(0).to(ONLINE).during(Duration.ofSeconds(RAMP)),
                        constantConcurrentUsers(ONLINE).during(Duration.ofSeconds(DURATION))),
                talker.injectClosed(
                        rampConcurrentUsers(0).to(TALKERS).during(Duration.ofSeconds(RAMP)),
                        constantConcurrentUsers(TALKERS).during(Duration.ofSeconds(DURATION)))
        ).protocols(httpProtocol).maxDuration(Duration.ofSeconds(DURATION + RAMP + 30));
    }
}
