package load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Wave/topology stress: a large <b>online pool</b> with a small, <b>rotating</b> set of <b>high-rate</b>
 * senders, in load waves — the "3000 msg/s spread over 30 talkers, 1200 online" scenario.
 *
 * <ul>
 *   <li><b>{@code load.pool}</b> clients stay <b>online</b> the whole run (hold a WS socket, receive).</li>
 *   <li>A rotating set of <b>active talkers</b> each fire {@code ~1000/ratePerMsg} msg/s into a chat with a
 *       <b>random pool peer</b> (all-to-all coverage via rotation), then leave so a fresh talker takes the
 *       slot. At full load {@code load.activeFull} talkers × ~100/s ≈ the target aggregate.</li>
 *   <li><b>Three waves</b> over {@code 3 × load.waveSec}: 50% → 100% → 75% of active-talker concurrency.</li>
 * </ul>
 *
 * Sends are rate-paced (not ack-gated) so the offered rate is actually delivered; server-side truth
 * (persisted rows, drops, GC, memory) is captured out-of-band by the sampler. Recipients are online,
 * so every message is also delivered live.
 *
 * <pre>
 *   cd loadtest &amp;&amp; JAVA_HOME=/path/to/jdk-21 mvn gatling:test \
 *       -Dgatling.simulationClass=load.MessengerWaveSimulation \
 *       -Dload.pool=1170 -Dload.activeFull=30 -Dload.ratePerMsgMs=10 -Dload.waveSec=140 -Dload.burst=400
 * </pre>
 */
public class MessengerWaveSimulation extends Simulation {

    private static final int POOL = Integer.getInteger("load.pool", 1170);          // recipient id space (u-0..u-(POOL-1))
    // Number of ACTUALLY-online pool receivers (each an ack-looping WS VU). Decoupled from POOL: a Gatling
    // WS ack-loop is CPU-heavy, so thousands of online VUs make the *generator* the bottleneck on a shared
    // host, not the app. Keep this small to run a talker-dominated inbound stress — talkers still address the
    // full POOL id space, so the surplus (POOL - ONLINE) are offline recipients that exercise the
    // deliver-offline → passive → watermark-advance path (fix #1) at high inbound rate. Defaults to POOL.
    private static final int ONLINE = Integer.getInteger("load.online", POOL);
    private static final int FULL = Integer.getInteger("load.activeFull", 30);      // concurrent talkers at 100%
    private static final int RATE_MS = Integer.getInteger("load.ratePerMsgMs", 10); // 10ms → ~100 msg/s per talker
    private static final int WAVE = Integer.getInteger("load.waveSec", 140);        // per-wave seconds (×3 ≈ 7 min)
    private static final int BURST = Integer.getInteger("load.burst", 400);         // msgs per talker bout (rotation)
    private static final int ONLINE_ACKS = Integer.getInteger("load.onlineAcks", 100); // messages a pool client ACKs per online session before it reconnects (C churn)
    private static final String BASE = System.getProperty("load.baseUrl", "http://localhost:8080");
    private static final String WS_BASE = System.getProperty("load.wsUrl", "ws://localhost:8080");
    // Chat provisioning (POST /api/chats) is @AdminOnly → X-Admin-Key (ADR-006). Supply the
    // deployed messenger's hormiga.admin.key so the create step is authorised.
    private static final String ADMIN_KEY = System.getProperty("load.adminKey", "");

    private static final int HALF = Math.max(1, FULL / 2);
    private static final int THREE_Q = Math.max(1, (FULL * 3) / 4);
    private static final int TOTAL = WAVE * 3;

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE).wsBaseUrl(WS_BASE)
            .contentTypeHeader("application/json").acceptHeader("application/json");

    private static final String[] BODIES = {
            "Здравствуйте! Когда удобно?", "Цена окончательная?", "Могу подъехать завтра",
            "Отправил фото, посмотрите", "Спасибо, договорились", "А гарантия есть?",
            "Давайте созвонимся", "Жду по адресу", "Готово, принимайте", "Уточните детали пожалуйста"
    };

    // Pool ids u-0, u-1, … handed out sequentially and WITHOUT bound. Corner-case fix: a finite feeder
    // (exactly POOL entries, each taken once) runs dry the moment injectClosed's constantConcurrentUsers
    // replaces a finished/crashed pool VU — Gatling then aborts the whole engine ("feeder is now empty").
    // An unbounded generator never starves: the first POOL ids (u-0..u-(POOL-1)) are the talkers' target
    // space and stay online for the run; any replacement VU gets a fresh id ≥ POOL and just holds a socket.
    private static final java.util.concurrent.atomic.AtomicInteger poolSeq = new java.util.concurrent.atomic.AtomicInteger();
    private static final Iterator<Map<String, Object>> poolFeeder = java.util.stream.Stream.generate(() -> {
        Map<String, Object> m = new HashMap<>();
        m.put("poolId", "u-" + poolSeq.getAndIncrement());
        return m;
    }).iterator();

    private static final Iterator<Map<String, Object>> msgFeeder = msgGen();

    private static Iterator<Map<String, Object>> msgGen() {
        return java.util.stream.Stream.generate(() -> {
            Map<String, Object> m = new HashMap<>();
            m.put("body", BODIES[ThreadLocalRandom.current().nextInt(BODIES.length)]);
            m.put("mid", UUID.randomUUID().toString());
            m.put("now", System.currentTimeMillis());
            return m;
        }).iterator();
    }

    private static final String CHAT_IN =
            "{\"type\":\"CHAT_IN\",\"senderId\":\"#{mId}\",\"recipientId\":\"#{peer}\","
                    + "\"conversationId\":\"#{conv}\",\"messageId\":\"#{mid}\",\"senderTimestamp\":#{now},"
                    + "\"senderTimezone\":\"UTC\",\"payload\":{\"kind\":\"text\",\"body\":\"#{body}\"}}";

    // ACK a delivered CHAT_OUT: senderId=me (recipient), recipientId=the original sender, correlationId=its messageId.
    private static final String CHAT_ACK =
            "{\"type\":\"CHAT_ACK\",\"senderId\":\"#{poolId}\",\"recipientId\":\"#{rsender}\","
                    + "\"conversationId\":\"#{rconv}\",\"messageId\":\"#{ackMid}\",\"correlationId\":\"#{rmid}\","
                    + "\"senderTimestamp\":#{ackTs},\"senderTimezone\":\"UTC\"}";

    // Online pool — REALISTIC receiver (A + C):
    //  A (prompt-ACK): drain the delivery buffer, ACKing EVERY CHAT_OUT the instant it arrives (tight loop,
    //     short await). Real clients ACK continuously, so per-recipient pending stays near zero, the GC
    //     watermark keeps advancing and the outbox drains — the Tetris model only holds under prompt ACK.
    //     (The old 1:1 loop with a 30s await-first gate + 10s awaits lagged under load → pending piled up →
    //     one slow client pinned the global watermark and stalled GC for everyone.)
    //  C (reconnect + history-sync): the whole run is a reconnect loop. On every (re)connect the client
    //     first GETs /api/history to catch up on anything it missed while away — the real recovery path
    //     after a network drop or a reaper eviction — then holds the socket, ACKs, closes, and reconnects
    //     after a short offline gap (presence churn). This exercises reap → reconnect → resync end-to-end.
    private final ScenarioBuilder online = scenario("online-pool")
            .feed(poolFeeder)
            .during(Duration.ofSeconds(TOTAL + 20)).on(
                    // C: REST catch-up on (re)connect (idempotent read; tolerate empty / not-found)
                    exec(http("pool-history-sync").get("/api/history")
                            .header("X-User-Id", "#{poolId}").header("X-User", "C")
                            .header("X-Role", "CLIENT").header("X-User-Email", "#{poolId}@load")
                            .check(status().in(200, 204, 404)))
                    // (re)connect; prime the first pending delivery (short await — an idle client just moves on)
                    .exec(ws("pool-connect").wsName("c").connect("/ws")
                            .header("X-User-Id", "#{poolId}").header("X-User", "C")
                            .header("X-Role", "CLIENT").header("X-User-Email", "#{poolId}@load")
                            .await(5).on(
                                    ws.checkTextMessage("await-first")
                                            .matching(jsonPath("$.type").is("CHAT_OUT"))
                                            .check(jsonPath("$.messageId").saveAs("rmid"))
                                            .check(jsonPath("$.senderId").saveAs("rsender"))
                                            .check(jsonPath("$.conversationId").saveAs("rconv"))))
                    // A: prompt-ACK — ack the message just received, then immediately await+capture the next.
                    .doIf(session -> session.contains("rmid")).then(
                            repeat(ONLINE_ACKS).on(
                                    exec(session -> session
                                            .set("ackMid", "ack-" + UUID.randomUUID())
                                            .set("ackTs", System.currentTimeMillis()))
                                            .exec(ws("pool-ack").wsName("c").sendText(CHAT_ACK)
                                                    .await(3).on(
                                                            ws.checkTextMessage("await-next")
                                                                    .matching(jsonPath("$.type").is("CHAT_OUT"))
                                                                    .check(jsonPath("$.messageId").saveAs("rmid"))
                                                                    .check(jsonPath("$.senderId").saveAs("rsender"))
                                                                    .check(jsonPath("$.conversationId").saveAs("rconv"))))
                            )
                    )
                    .exec(ws("pool-close").wsName("c").close())
                    .exec(session -> session.remove("rmid"))
                    // short offline gap before reconnect (presence churn); the next loop re-syncs history
                    .pause(Duration.ofSeconds(1), Duration.ofSeconds(3))
            );

    // A rotating high-rate talker: pick a random online peer, open a chat, fire ~100/s for a short bout, leave.
    private final ScenarioBuilder talker = scenario("active-talkers")
            .exec(session -> session
                    .set("mId", "t-" + UUID.randomUUID())
                    .set("peer", "u-" + ThreadLocalRandom.current().nextInt(POOL)))
            .exec(http("wave-create-chat").post("/api/chats")
                    .header("X-Admin-Key", ADMIN_KEY)
                    .header("X-User-Id", "load-svc").header("X-User", "Load Service")
                    .header("X-Role", "SERVICE").header("X-User-Email", "svc@load")
                    .body(StringBody("{\"clientId\":\"#{peer}\",\"masterId\":\"#{mId}\",\"metadata\":{\"orderId\":\"#{mId}\"}}"))
                    .check(status().in(200, 201), jsonPath("$.id").saveAs("conv")))
            // Guard: only open the socket and fire the burst if the chat was actually created. If the
            // create failed (e.g. a saturated single-host load generator refusing connections), #{conv} is
            // unset — sending CHAT_IN then would flood the run with "No attribute named 'conv'" cascades
            // (one per burst message). Skip the bout cleanly instead; this talker slot just rotates.
            .doIf(session -> session.contains("conv")).then(
                    exec(ws("wave-connect").wsName("m").connect("/ws")
                            .header("X-User-Id", "#{mId}").header("X-User", "M")
                            .header("X-Role", "MASTER").header("X-User-Email", "#{mId}@load"))
                            .repeat(BURST).on(
                                    feed(msgFeeder)
                                            .exec(ws("wave-send").wsName("m").sendText(CHAT_IN))
                                            .pace(Duration.ofMillis(RATE_MS)) // ~1000/RATE_MS msg/s per talker
                            )
                            .exec(ws("wave-close").wsName("m").close())
            );

    {
        setUp(
                online.injectClosed(
                        rampConcurrentUsers(0).to(ONLINE).during(Duration.ofSeconds(30)),
                        constantConcurrentUsers(ONLINE).during(Duration.ofSeconds(TOTAL))),
                talker.injectClosed(
                        // wave 1 — 50%
                        rampConcurrentUsers(0).to(HALF).during(Duration.ofSeconds(10)),
                        constantConcurrentUsers(HALF).during(Duration.ofSeconds(WAVE - 10)),
                        // wave 2 — 100%
                        rampConcurrentUsers(HALF).to(FULL).during(Duration.ofSeconds(5)),
                        constantConcurrentUsers(FULL).during(Duration.ofSeconds(WAVE - 5)),
                        // wave 3 — 75%
                        constantConcurrentUsers(THREE_Q).during(Duration.ofSeconds(WAVE)))
        ).protocols(httpProtocol).maxDuration(Duration.ofSeconds(TOTAL + 90));
    }
}
