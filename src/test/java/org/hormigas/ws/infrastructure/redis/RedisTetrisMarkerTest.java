package org.hormigas.ws.infrastructure.redis;
import org.hormigas.hl.redis.RedisTetrisMarker;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.RedisAPI;
import jakarta.inject.Inject;
import org.hormigas.hl.contracts.stage.StageResult;
import org.hormigas.ws.domain.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class RedisTetrisMarkerTest {

    @Inject
    RedisTetrisMarker tetris;

    @Inject
    RedisAPI redis;

    private String recipient1;
    private String recipient2;

    @BeforeEach
    public void setup() {
        recipient1 = UUID.randomUUID().toString();
        recipient2 = UUID.randomUUID().toString();
        redis.flushall(List.of()).await().indefinitely();
    }

    @Test
    public void testOnSentAndComputeSafeDeleteId() {
        Message message1 = msg(1L, recipient1);
        Message message2 = msg(2L, recipient1);
        onSent(message1).await().indefinitely();
        onSent(message2).await().indefinitely();

        Message message10 = msg(10L, recipient2);
        onSent(message10).await().indefinitely();

        Long globalSafe = tetris.computeGlobalSafeDeleteId().await().indefinitely();
        assertEquals(1L, globalSafe);

        var gz = redis.zrange(List.of("tetris:minids", "0", "-1")).await().indefinitely();
        assertNotNull(gz);
    }

    @Test
    public void testAckAdvancesNextExpectedId() {
        Message message3 = msg(3L, recipient1);
        Message message4 = msg(4L, recipient1);
        Message message6 = msg(6L, recipient1);

        onSent(message3).await().indefinitely();
        onSent(message4).await().indefinitely();
        onSent(message6).await().indefinitely();

        onAck(message6).await().indefinitely();
        assertEquals(3L, tetris.computeGlobalSafeDeleteId().await().indefinitely());

        onAck(message3).await().indefinitely();
        assertEquals(4L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testAckOutOfOrder() {
        Message message1 = msg(1L, recipient1);
        Message message2 = msg(2L, recipient1);
        Message message3 = msg(3L, recipient1);

        onSent(message1).await().indefinitely();
        onSent(message2).await().indefinitely();
        onSent(message3).await().indefinitely();

        onAck(message3).await().indefinitely();
        assertEquals(1L, tetris.computeGlobalSafeDeleteId().await().indefinitely());

        onAck(message1).await().indefinitely();
        assertEquals(2L, tetris.computeGlobalSafeDeleteId().await().indefinitely());

        onAck(message2).await().indefinitely();
        assertEquals(4L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testDuplicateAckIsSafe() {
        Message message1 = msg(1L, recipient1);
        Message message2 = msg(2L, recipient1);
        onSent(message1).await().indefinitely();
        onSent(message2).await().indefinitely();

        onAck(message1).await().indefinitely();
        onAck(message1).await().indefinitely();

        // after acking 1, min unacked == 2 => global safe for single-client scenario becomes 2,
        // and with no other clients present computeGlobalSafeDeleteId() should return 2
        assertEquals(2L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testGapAckDoesNotAdvanceSafeDelete() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(3L, recipient1)).await().indefinitely();

        // ack middle one only
        onAck(msg(2L, recipient1)).await().indefinitely();

        // earliest unacked is still 1 -> safe remains 1
        assertEquals(1L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testLateAcksCatchUp() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(3L, recipient1)).await().indefinitely();

        onAck(msg(3L, recipient1)).await().indefinitely();
        assertEquals(1L, tetris.computeGlobalSafeDeleteId().await().indefinitely());

        onAck(msg(1L, recipient1)).await().indefinitely();
        onAck(msg(2L, recipient1)).await().indefinitely();

        assertEquals(4L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testAckBeforeAnySentIsIgnored() {
        onAck(msg(5L, recipient1)).await().indefinitely();
        assertEquals(0L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testDisconnectAdvancesSafeDelete() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(3L, recipient1)).await().indefinitely();

        tetris.onDisconnect(recipient1).await().indefinitely();

        // onDisconnect should set safe to highestSentId (3)
        assertEquals(4L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testDisconnectBeforeAnySentDoesNothing() {
        tetris.onDisconnect(recipient1).await().indefinitely();
        assertEquals(0L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testDisconnectHandlesGaps() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(3L, recipient1)).await().indefinitely();
        onSent(msg(4L, recipient1)).await().indefinitely();

        onAck(msg(1L, recipient1)).await().indefinitely();

        tetris.onDisconnect(recipient1).await().indefinitely();

        // highestSentId = 4 -> safe = 4
        assertEquals(5L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testAckExactlyAtDisconnectCutoff() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();

        tetris.onDisconnect(recipient1).await().indefinitely(); // safe = 2

        onSent(msg(3L, recipient1)).await().indefinitely();
        onAck(msg(3L, recipient1)).await().indefinitely();

        assertEquals(4L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testDisconnectCleansAckZset() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();

        tetris.onDisconnect(recipient1).await().indefinitely();
        String ackKey = "tetris:re:" + recipient1 + ":ack";

        String count = redis.zcount(ackKey, "-inf", "+inf")
                .await().indefinitely()
                .toString();

        // after disconnect we keep only the max (2) -> zset size == 1
        assertEquals("0", count);
        assertEquals(3L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testAckAfterDisconnectWorksNormally() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();

        tetris.onDisconnect(recipient1).await().indefinitely(); // safe = 2

        onSent(msg(3L, recipient1)).await().indefinitely();
        onAck(msg(3L, recipient1)).await().indefinitely();

        assertEquals(4L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testMultipleRecipients() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(10L, recipient2)).await().indefinitely();

        onAck(msg(1L, recipient1)).await().indefinitely();
        onAck(msg(10L, recipient2)).await().indefinitely();

        Long globalSafe = tetris.computeGlobalSafeDeleteId().await().indefinitely();
        assertEquals(2L, globalSafe);
    }

    @Test
    public void testOneRecipientBlocksGlobal() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(10L, recipient2)).await().indefinitely();
        onSent(msg(11L, recipient2)).await().indefinitely();

        onAck(msg(10L, recipient2)).await().indefinitely();
        onAck(msg(11L, recipient2)).await().indefinitely();

        Long globalSafe = tetris.computeGlobalSafeDeleteId().await().indefinitely();
        assertEquals(1L, globalSafe);
    }

    @Test
    public void testScanMultiIteration() {
        for (int i = 0; i < 150; i++) {
            String r = UUID.randomUUID().toString();
            onSent(msg(5L, r)).await().indefinitely();
            onAck(msg(5L, r)).await().indefinitely();
        }

        Long globalSafe = tetris.computeGlobalSafeDeleteId().await().indefinitely();
        assertEquals(6L, globalSafe);
    }

    @Test
    public void testMultipleClientsOneEmptyOneNot() {
        // recipient1 fully acked
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onAck(msg(1L, recipient1)).await().indefinitely();
        onAck(msg(2L, recipient1)).await().indefinitely();

        // recipient2 has unacked messages
        onSent(msg(10L, recipient2)).await().indefinitely();
        onSent(msg(12L, recipient2)).await().indefinitely();

        Long globalSafe = tetris.computeGlobalSafeDeleteId().await().indefinitely();
        assertEquals(10L, globalSafe);

        var z1 = redis.zrange(List.of("tetris:re:" + recipient1 + ":ack", "0", "-1")).await().indefinitely();
        assertTrue(z1.size()==0, "recipient1 acks should be empty");

        var z2 = redis.zrange(List.of("tetris:re:" + recipient2 + ":ack", "0", "-1")).await().indefinitely();
        assertEquals(List.of("10", "12").toString(), z2.toString(), "recipient2 should have unacked messages");
    }

    @Test
    public void testZeroIdsIgnored() {
        onSent(msg(0L, recipient1)).await().indefinitely();
        onSent(msg(5L, recipient2)).await().indefinitely();

        Long globalSafe = tetris.computeGlobalSafeDeleteId().await().indefinitely();
        assertEquals(5L, globalSafe);
    }

    @Test
    public void testGlobalSafeBecomesMinusOneIfAllZeroOrEmpty() {
        onSent(msg(0L, recipient1)).await().indefinitely();
        onAck(msg(0L, recipient1)).await().indefinitely();

        onSent(msg(0L, recipient2)).await().indefinitely();
        onAck(msg(0L, recipient2)).await().indefinitely();

        Long globalSafe = tetris.computeGlobalSafeDeleteId().await().indefinitely();
        assertEquals(0L, globalSafe);
    }

    @Test
    public void testFindHeavyClientsNone() {
        // no clients -> none heavy
        var heavy = tetris.findHeavyClients(2, 10).await().indefinitely();
        assertTrue(heavy.isEmpty(), "No heavy clients expected");
    }

    @Test
    public void testFindHeavyClientsAllBelowThreshold() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(1L, recipient2)).await().indefinitely();

        var heavy = tetris.findHeavyClients(3, 10).await().indefinitely();
        assertTrue(heavy.isEmpty(), "No client exceeds threshold 3");
    }

    @Test
    public void testFindHeavyClientsSomeAboveThreshold() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(3L, recipient1)).await().indefinitely();

        onSent(msg(1L, recipient2)).await().indefinitely();

        var heavy = tetris.findHeavyClients(2, 10).await().indefinitely();
        assertEquals(1, heavy.size());
        assertTrue(heavy.contains(recipient1));
        assertFalse(heavy.contains(recipient2));
    }

    @Test
    public void testFindHeavyClientsRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            String r = UUID.randomUUID().toString();
            onSent(msg(1L, r)).await().indefinitely();
            onSent(msg(2L, r)).await().indefinitely();
            onSent(msg(3L, r)).await().indefinitely();
        }

        var heavy = tetris.findHeavyClients(2, 3).await().indefinitely();
        assertEquals(3, heavy.size(), "Should respect limit");
    }

    @Test
    public void testFindHeavyClientsIgnoresAcked() {
        // recipient1 = 3 unacked initially, then ack 2
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        onSent(msg(3L, recipient1)).await().indefinitely();

        onAck(msg(2L, recipient1)).await().indefinitely();

        var heavy = tetris.findHeavyClients(2, 10).await().indefinitely();
        assertEquals(1, heavy.size());
        assertTrue(heavy.contains(recipient1));
    }

    @Test
    public void testFindHeavyClientsWithZeroIds() {
        onSent(msg(0L, recipient1)).await().indefinitely();
        onSent(msg(1L, recipient2)).await().indefinitely();
        onSent(msg(2L, recipient2)).await().indefinitely();
        onSent(msg(3L, recipient2)).await().indefinitely();

        var heavy = tetris.findHeavyClients(2, 10).await().indefinitely();
        assertEquals(1, heavy.size());
        assertTrue(heavy.contains(recipient2));
        assertFalse(heavy.contains(recipient1));
    }

    @Test
    public void testPrimedFlagAndRehydrateFromOutbox() {
        // fresh Redis (flushed in setup) → state is not primed
        assertFalse(tetris.isPrimed().await().indefinitely());

        // rebuild pending state from the durable outbox (recipient → ids)
        tetris.rehydrate(java.util.Map.of(
                recipient1, List.of(5L, 6L),
                recipient2, List.of(9L))).await().indefinitely();

        // now primed, and the safe-delete id is the global min pending (5)
        assertTrue(tetris.isPrimed().await().indefinitely());
        assertEquals(5L, tetris.computeGlobalSafeDeleteId().await().indefinitely());

        // an ack on the lowest id advances the boundary to the next pending (6)
        onAck(msg(5L, recipient1)).await().indefinitely();
        assertEquals(6L, tetris.computeGlobalSafeDeleteId().await().indefinitely());
    }

    @Test
    public void testNotPrimedAfterStateLoss() {
        tetris.rehydrate(java.util.Map.of(recipient1, List.of(1L))).await().indefinitely();
        assertTrue(tetris.isPrimed().await().indefinitely());
        redis.flushall(List.of()).await().indefinitely();
        assertFalse(tetris.isPrimed().await().indefinitely(), "flushed Redis must read as unprimed");
    }

    @Test
    public void testAckOfNonPendingIdDoesNotDriftCount() {
        // two genuinely pending ids → count 2
        onSent(msg(1L, recipient1)).await().indefinitely();
        onSent(msg(2L, recipient1)).await().indefinitely();
        // a late/duplicate ACK of an id that is NOT pending must not decrement the count (T4)
        onAck(msg(99L, recipient1)).await().indefinitely();
        // count still reflects the 2 real pending ids → recipient is still "heavy" at threshold 2
        var heavy = tetris.findHeavyClients(2, 10).await().indefinitely();
        assertTrue(heavy.contains(recipient1), "count must not drift below the true pending size");
    }

    @Test
    public void testDisconnectRemovesCountField() {
        onSent(msg(1L, recipient1)).await().indefinitely();
        assertNotNull(redis.hget("tetris:re:cnt", recipient1).await().indefinitely(),
                "count field present while pending");
        tetris.onDisconnect(recipient1).await().indefinitely();
        // T3: HDEL removes the field entirely (previously HSET 0 leaked a field per client forever)
        assertNull(redis.hget("tetris:re:cnt", recipient1).await().indefinitely(),
                "count field removed on disconnect");
    }

    @Test
    public void testWipeColdStateAlwaysWipesEvenWhenPrimed() {
        onSent(msg(5L, recipient1)).await().indefinitely();          // prior-process state
        redis.set(List.of("tetris:primed", "1")).await().indefinitely();   // persisted primed from a dead process
        // Ghost-leak fix: a fresh JVM owns no live in-memory state, so a persisted primed flag must NOT
        // preserve a previous process's snapshot (which may hold orphan minids with no outbox row). Wipe
        // is now unconditional and also clears primed, so the next GC cycle rehydrates from the durable outbox.
        assertTrue(tetris.wipeColdState(), "T6: startup wipe runs unconditionally, even when primed was set");
        var z = redis.zrange(List.of("tetris:re:" + recipient1 + ":ack", "0", "-1")).await().indefinitely();
        assertEquals(0, z.size(), "prior-process state cleared on restart");
        var primed = redis.exists(List.of("tetris:primed")).await().indefinitely();
        assertEquals(0, primed.toInteger(), "primed itself is cleared so GC re-rehydrates from the outbox");
    }

    @Test
    public void testWipeColdStateClearsWhenNotPrimed() {
        onSent(msg(5L, recipient1)).await().indefinitely();          // creates keys, primed NOT set
        assertTrue(tetris.wipeColdState(), "T6: a cold (unprimed) Redis is wiped");
        var z = redis.zrange(List.of("tetris:re:" + recipient1 + ":ack", "0", "-1")).await().indefinitely();
        assertEquals(0, z.size(), "stale state cleared on cold start");
    }

    @Test
    public void testOnSkippedAdvancesLastIdFallbackWithoutPinning() {
        // Offline delivery: advance the lastid fallback so GC can reclaim the backlog, but add nothing to
        // minids (no online recipient to ACK it). With nothing pending, safe-delete falls back to lastid+1.
        onSkipped(msg(500L, recipient1)).await().indefinitely();
        assertEquals(501L, (long) tetris.computeGlobalSafeDeleteId().await().indefinitely(),
                "offline-skipped id must advance the safe-delete fallback so its outbox row is GC-eligible");
        // A genuinely pending (online) message still wins by min-precedence — onSkipped can never over-trim.
        onSent(msg(300L, recipient2)).await().indefinitely();
        assertEquals(300L, (long) tetris.computeGlobalSafeDeleteId().await().indefinitely(),
                "a pending online message still gates GC ahead of the lastid fallback");
    }

    // helper to build Message objects used by tests
    private Message msg(long id, String recipientId) {
        return Message.builder()
                .id(id)
                .ackId(id)
                .recipientId(recipientId)
                .senderId(recipientId)
                .build();
    }

    // the primitive TetrisMarker port takes ids, not a Message — these adapt the tests' Message objects
    private Uni<StageResult<Void>> onSent(Message m) { return tetris.onSent(m.getRecipientId(), m.getId()); }
    private Uni<StageResult<Void>> onAck(Message m) { return tetris.onAck(m.getSenderId(), m.getAckId()); }
    private Uni<StageResult<Void>> onSkipped(Message m) { return tetris.onSkipped(m.getId()); }
}
