package org.hormigas.ws.infrastructure.persistance.postgres.conversation.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.ws.domain.conversation.ChatQuery;
import org.hormigas.ws.domain.conversation.ChatStats;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.ports.conversation.ConversationManager;

import java.time.Duration;
import java.util.List;

/**
 * The default {@link ConversationManager} the core injects — a caching adapter (ADR-020) that fronts the
 * {@link PersistenceBacked} Postgres adapter with an in-process L1 (Caffeine). Caching is thereby pure
 * infrastructure: the core sees ONE port and never knows a cache exists (no second port, no explicit
 * {@code invalidate} calls in the use-cases).
 *
 * <p>The send-guard reads membership/block per inbound message via {@link #findById}; without this each was
 * a Postgres round-trip. Writes are <b>write-through</b>: {@link #hideFor}, {@link #setBlocked} and
 * {@link #advanceReadReceipts} evict the affected conversation(s) so a subsequent read is fresh (e.g. the
 * peerLastReadId a history read returns). A short {@code expireAfterWrite} TTL bounds staleness regardless.
 *
 * <p>Single-instance correct. Multi-instance (Redis L2 + cross-node invalidation) is a further layer behind
 * this same port — deferred until the service runs as more than one instance.
 */
@ApplicationScoped
public class CachingConversationManager implements ConversationManager {

    @Inject
    @PersistenceBacked
    ConversationManager delegate;

    @Inject
    MessengerConfig config;

    private Cache<String, Conversation> cache;

    @PostConstruct
    void init() {
        cache = Caffeine.newBuilder()
                .maximumSize(config.conversationCache().maxSize())
                .expireAfterWrite(Duration.ofSeconds(config.conversationCache().ttlSeconds()))
                .build();
    }

    @Override
    public Uni<Conversation> findById(String id) {
        Conversation hit = cache.getIfPresent(id);
        if (hit != null) {
            return Uni.createFrom().item(hit);
        }
        // Miss → load and populate. Absent conversations are NOT negatively cached (rare, may be created
        // imminently; the next lookup loads fresh).
        return delegate.findById(id).invoke(loaded -> {
            if (loaded != null) {
                cache.put(id, loaded);
            }
        });
    }

    @Override
    public Uni<Conversation> findByPair(String clientId, String masterId) {
        return delegate.findByPair(clientId, masterId);
    }

    @Override
    public Uni<List<Conversation>> findByParticipant(String userId) {
        return delegate.findByParticipant(userId);
    }

    @Override
    public Uni<Upserted> upsertPair(Conversation conversation) {
        // Insert-or-reuse: a fresh insert has nothing cached; a reused existing row is returned untouched
        // (DO NOTHING) so any cached copy stays valid. No eviction needed.
        return delegate.upsertPair(conversation);
    }

    @Override
    public Uni<Void> hideFor(String chatId, String userId) {
        return delegate.hideFor(chatId, userId).invoke(() -> cache.invalidate(chatId));
    }

    @Override
    public Uni<Void> setBlocked(String chatId, String userId, boolean blocked) {
        return delegate.setBlocked(chatId, userId, blocked).invoke(() -> cache.invalidate(chatId));
    }

    @Override
    public Uni<Void> advanceReadReceipts(List<ReadReceiptMark> marks) {
        return delegate.advanceReadReceipts(marks)
                .invoke(() -> marks.stream().map(ReadReceiptMark::conversationId).distinct()
                        .forEach(cache::invalidate));
    }

    @Override
    public Uni<List<Conversation>> findAll(ChatQuery query) {
        return delegate.findAll(query);
    }

    @Override
    public Uni<Long> count(ChatQuery query) {
        return delegate.count(query);
    }

    @Override
    public Uni<ChatStats> stats() {
        return delegate.stats();
    }
}
