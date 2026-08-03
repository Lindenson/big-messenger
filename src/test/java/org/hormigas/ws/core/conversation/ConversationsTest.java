package org.hormigas.ws.core.conversation;

import io.smallrye.mutiny.Uni;
import org.hormigas.ws.domain.conversation.result.CreateResult;
import org.hormigas.ws.domain.conversation.result.CreateOutcome;
import org.hormigas.ws.domain.conversation.result.GuardedOutcome;
import org.hormigas.ws.domain.conversation.result.SendCheck;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.generator.IdGenerator;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.ports.conversation.ConversationManager;
import org.hormigas.ws.ports.history.History;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the chat use case (no infra). Verifies FR-CHAT-02 (idempotent create),
 * membership/authz outcomes, and the send-guard (UC-H07 / FR-MSG-01).
 */
class ConversationsTest {

    private static Conversation conv(boolean clientBlocked, boolean masterBlocked) {
        Instant now = Instant.now();
        return new Conversation("conv-1", "client-1", "master-1", Map.of(),
                clientBlocked, masterBlocked, now, now);
    }

    private static Conversation convDeleted(String deletedFromClient, String deletedFromMaster) {
        Instant now = Instant.now();
        return new Conversation("conv-1", "client-1", "master-1", Map.of(),
                false, false, deletedFromClient, deletedFromMaster, null, null, now, now);
    }

    private Conversations service(StubRepo repo) {
        Conversations s = new Conversations();
        s.repository = repo;
        s.idGenerator = () -> "gen-1";
        // the guard reads through the same (single) manager port — the stub repo (ADR-020: no separate cache port).
        s.guard = new org.hormigas.ws.core.conversation.guard.ConversationGuard(
                repo, new org.hormigas.ws.core.conversation.guard.ConversationAccess());
        return s;
    }

    // ── createChat (idempotent) ────────────────────────────────────────────────

    @Test
    void createChat_new_pair_creates() {
        StubRepo repo = new StubRepo();           // findByPair → null (no existing)
        CreateResult r = service(repo).createChat("client-1", "master-1", Map.of("orderId", "o-1"))
                .await().indefinitely();
        assertTrue(r.created());
        assertEquals("gen-1", r.conversation().id());
        assertEquals("client-1", r.conversation().clientId());
        assertEquals("o-1", r.conversation().metadata().get("orderId"));
        assertNotNull(repo.inserted);
    }

    @Test
    void createChat_existing_pair_returns_existing() {
        StubRepo repo = new StubRepo();
        repo.pair = conv(false, false);           // an existing conversation for the pair
        CreateResult r = service(repo).createChat("client-1", "master-1", Map.of()).await().indefinitely();
        assertFalse(r.created());
        assertEquals("conv-1", r.conversation().id());
        assertNull(repo.inserted, "must not insert when one already exists");
    }

    @Test
    void createChat_soft_deleted_pair_returns_existing_without_reviving() {
        // #4b — a "deleted-for-me" pair is NOT force-revived on re-create: it is returned as-is and the
        // first new message (messageId > the delete watermark) reveals it, so old deleted messages stay
        // hidden. (Revival is message-driven — no watermark clearing.)
        StubRepo repo = new StubRepo();
        repo.pair = convDeleted("WM-CLIENT", null); // existing but soft-deleted for the client
        CreateResult r = service(repo).createChat("client-1", "master-1", Map.of("orderId", "o-2"))
                .await().indefinitely();
        assertFalse(r.created(), "returning an existing pair is not a new create");
        assertEquals(CreateOutcome.EXISTING, r.outcome(), "returned as-is, not revived");
        assertEquals("conv-1", r.conversation().id());
        assertNull(repo.inserted, "must not insert when the pair already exists");
    }

    @Test
    void createChat_blocked_pair_is_refused() {
        // #3 — provisioning over a blocked pair is refused (→ 409), never silently overriding the block.
        StubRepo repo = new StubRepo();
        repo.pair = conv(true, false);              // existing + client-blocked
        CreateResult r = service(repo).createChat("client-1", "master-1", Map.of()).await().indefinitely();
        assertTrue(r.blocked(), "a blocked pair is refused");
        assertEquals(CreateOutcome.BLOCKED, r.outcome());
        assertNull(repo.inserted, "no new chat inserted for a blocked pair");
    }

    // ── hide / block outcomes (membership-checked) ──────────────────────────────

    @Test
    void hide_and_block_outcomes() {
        StubRepo repo = new StubRepo();
        repo.byId = null;
        assertEquals(GuardedOutcome.NOT_FOUND, service(repo).hide("conv-1", "master-1").await().indefinitely());

        repo.byId = conv(false, false);
        assertEquals(GuardedOutcome.FORBIDDEN, service(repo).hide("conv-1", "outsider").await().indefinitely());
        assertEquals(GuardedOutcome.OK, service(repo).hide("conv-1", "master-1").await().indefinitely());
        assertEquals(GuardedOutcome.OK, service(repo).setBlocked("conv-1", "client-1", true).await().indefinitely());
        assertEquals(GuardedOutcome.FORBIDDEN, service(repo).setBlocked("conv-1", "outsider", true).await().indefinitely());
    }

    // ── history "delete for me" watermark (D3) ──────────────────────────────────

    @Test
    void history_no_watermark_reads_from_since() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        StubHistory hist = new StubHistory();
        Conversations s = service(repo); s.history = hist;
        var page = s.history("conv-1", "client-1", null, null, null, false).await().indefinitely();
        assertEquals(GuardedOutcome.OK, page.outcome());
        assertEquals(2, page.value().messages().size());
        assertNull(hist.lastAfter, "no watermark and no cursor → no floor (reads the latest page)");
    }

    @Test
    void history_floored_at_the_callers_delete_watermark() {
        StubRepo repo = new StubRepo(); repo.byId = convDeleted("WM-CLIENT", null);
        StubHistory hist = new StubHistory();
        Conversations s = service(repo); s.history = hist;
        s.history("conv-1", "client-1", null, null, null, false).await().indefinitely();
        assertEquals("WM-CLIENT", hist.lastAfter, "the deleter's scroll-up is floored at their watermark");
    }

    @Test
    void history_uses_the_later_of_since_and_watermark() {
        StubRepo repo = new StubRepo(); repo.byId = convDeleted("WM-CLIENT", null);
        StubHistory hist = new StubHistory();
        Conversations s = service(repo); s.history = hist;
        s.history("conv-1", "client-1", "WM-CLIENTZ", null, null, false).await().indefinitely(); // since after wm
        assertEquals("WM-CLIENTZ", hist.lastSince);
        s.history("conv-1", "client-1", "WM-AAAA", null, null, false).await().indefinitely();    // since before wm
        assertEquals("WM-CLIENT", hist.lastSince, "the watermark floors a stale forward cursor");
    }

    @Test
    void history_includeDeleted_bypasses_the_watermark() {
        StubRepo repo = new StubRepo(); repo.byId = convDeleted("WM-CLIENT", null);
        StubHistory hist = new StubHistory();
        Conversations s = service(repo); s.history = hist;
        s.history("conv-1", "client-1", null, null, null, true).await().indefinitely();
        assertNull(hist.lastAfter, "includeDeleted ignores the watermark → no floor");
    }

    @Test
    void history_peer_who_did_not_delete_is_unfiltered() {
        StubRepo repo = new StubRepo(); repo.byId = convDeleted("WM-CLIENT", null); // only client deleted
        StubHistory hist = new StubHistory();
        Conversations s = service(repo); s.history = hist;
        s.history("conv-1", "master-1", null, null, null, false).await().indefinitely();
        assertNull(hist.lastAfter, "the peer who didn't delete has no floor");
    }

    @Test
    void history_before_cursor_pages_backward() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        StubHistory hist = new StubHistory();
        Conversations s = service(repo); s.history = hist;
        s.history("conv-1", "client-1", null, "BEFORE-XYZ", null, false).await().indefinitely();
        assertEquals("BEFORE-XYZ", hist.lastBefore, "before is the exclusive upper bound for scroll-up");
        assertNull(hist.lastAfter, "no delete watermark → no floor");
    }

    @Test
    void history_since_wins_over_before_and_takes_the_forward_path() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        StubHistory hist = new StubHistory();
        Conversations s = service(repo); s.history = hist;
        s.history("conv-1", "client-1", "SINCE-1", "BEFORE-1", null, false).await().indefinitely();
        assertEquals("SINCE-1", hist.lastSince, "since present → forward catch-up path");
        assertNull(hist.lastBefore, "the backward path is not taken when since is given");
    }

    // ── send-guard (canSend) ────────────────────────────────────────────────────

    @Test
    void canSend_allow_when_participant_and_not_blocked() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        assertEquals(SendCheck.ALLOW, service(repo).canSend("conv-1", "master-1").await().indefinitely());
    }

    @Test
    void canSend_noConversation_blank_or_missing() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        assertEquals(SendCheck.NO_CONVERSATION, service(repo).canSend("  ", "master-1").await().indefinitely());
        repo.byId = null;
        assertEquals(SendCheck.NO_CONVERSATION, service(repo).canSend("conv-1", "master-1").await().indefinitely());
    }

    @Test
    void canSend_notMember_and_blocked() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        assertEquals(SendCheck.NOT_MEMBER, service(repo).canSend("conv-1", "outsider").await().indefinitely());
        repo.byId = conv(true, false);
        assertEquals(SendCheck.BLOCKED, service(repo).canSend("conv-1", "master-1").await().indefinitely());
    }

    @Test
    void canSend_allowed_even_when_a_side_deleted_the_chat() {
        StubRepo repo = new StubRepo();
        // delete is "delete for me", not a messaging stop — both sides may still send
        repo.byId = convDeleted("WM-CLIENT", null);
        assertEquals(SendCheck.ALLOW, service(repo).canSend("conv-1", "master-1").await().indefinitely());
        assertEquals(SendCheck.ALLOW, service(repo).canSend("conv-1", "client-1").await().indefinitely());
    }

    // ── evaluateSend (S1: carries the conversation so the caller can derive the authentic recipient) ──

    @Test
    void evaluateSend_allow_returns_conversation() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        var decision = service(repo).evaluateSend("conv-1", "master-1").await().indefinitely();
        assertEquals(SendCheck.ALLOW, decision.check());
        assertNotNull(decision.conversation(), "ALLOW must carry the resolved conversation");
        assertEquals("client-1", decision.conversation().clientId());
        assertEquals("master-1", decision.conversation().masterId());
    }

    @Test
    void evaluateSend_nonAllow_has_null_conversation() {
        StubRepo repo = new StubRepo(); repo.byId = conv(false, false);
        assertNull(service(repo).evaluateSend("conv-1", "outsider").await().indefinitely().conversation(),
                "NOT_MEMBER must not leak a conversation");
        repo.byId = conv(true, false);
        assertNull(service(repo).evaluateSend("conv-1", "master-1").await().indefinitely().conversation(),
                "BLOCKED must not leak a conversation");
        repo.byId = null;
        assertNull(service(repo).evaluateSend("conv-1", "master-1").await().indefinitely().conversation());
    }

    /** Configurable in-memory stub. */
    static class StubRepo implements ConversationManager {
        Conversation pair;       // findByPair result
        Conversation byId;       // findById result
        Conversation inserted;   // captured insert
        public Uni<Conversation> findByPair(String c, String m) { return Uni.createFrom().item(pair); }
        public Uni<Conversation> findById(String id) { return Uni.createFrom().item(byId); }
        public Uni<List<Conversation>> findByParticipant(String u) { return Uni.createFrom().item(List.of()); }
        public Uni<ConversationManager.Upserted> upsertPair(Conversation c) {
            // Emulate the single-query upsert: an existing pair is reused (created=false, no insert);
            // otherwise the fresh row is inserted (created=true) and captured.
            if (pair != null) return Uni.createFrom().item(new ConversationManager.Upserted(pair, false));
            inserted = c;
            return Uni.createFrom().item(new ConversationManager.Upserted(c, true));
        }
        public Uni<Void> hideFor(String chatId, String userId) { return Uni.createFrom().voidItem(); }
        public Uni<Void> setBlocked(String chatId, String userId, boolean b) { return Uni.createFrom().voidItem(); }
        public Uni<Void> advanceReadReceipts(List<org.hormigas.ws.domain.conversation.ReadReceiptMark> marks) { return Uni.createFrom().voidItem(); }
        public Uni<List<Conversation>> findAll(org.hormigas.ws.domain.conversation.ChatQuery q) { return Uni.createFrom().item(List.of()); }
        public Uni<Long> count(org.hormigas.ws.domain.conversation.ChatQuery q) { return Uni.createFrom().item(0L); }
        public Uni<org.hormigas.ws.domain.conversation.ChatStats> stats() { return Uni.createFrom().item(new org.hormigas.ws.domain.conversation.ChatStats(0, 0)); }
    }

    /** History stub that records the effective floor (since) passed in and returns two messages. */
    static class StubHistory implements History<Message> {
        String lastSince;
        String lastBefore;
        String lastAfter;
        boolean queried;
        private List<Message> two() { return List.of(Message.builder().build(), Message.builder().build()); }
        public Uni<List<Message>> getByRecipientId(String c) { return Uni.createFrom().item(two()); }
        public Uni<List<Message>> getBySenderId(String c) { return Uni.createFrom().item(two()); }
        public Uni<List<Message>> getAllBySenderId(String c) { return Uni.createFrom().item(two()); }
        public Uni<List<Message>> getByConversation(String id) { queried = true; return Uni.createFrom().item(two()); }
        public Uni<List<Message>> getByConversation(String id, String since, int limit) {
            queried = true; lastSince = since; return Uni.createFrom().item(two());
        }
        public Uni<List<Message>> getByConversationBefore(String id, String before, String after, int limit) {
            queried = true; lastBefore = before; lastAfter = after; return Uni.createFrom().item(two());
        }
        public void addBySenderId(String c, Message m) { }
    }
}
