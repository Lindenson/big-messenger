package org.hormigas.ws.infrastructure.persistance.postgres.conversation.cache;

import io.smallrye.mutiny.Uni;
import org.hormigas.ws.config.MessengerConfig;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.domain.conversation.ReadReceiptMark;
import org.hormigas.ws.ports.conversation.ConversationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@DisplayName("CachingConversationManager — L1 cache over the persistence delegate: load-on-miss, "
        + "serve-from-cache, write-through invalidation")
class CachingConversationManagerTest {

    private ConversationManager delegate;
    private CachingConversationManager caching;

    private static final Conversation CHAT = new Conversation(
            "c1", "A", "M", Map.of(), false, false, Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        delegate = mock(ConversationManager.class);
        MessengerConfig config = mock(MessengerConfig.class);
        MessengerConfig.ConversationCache cacheCfg = mock(MessengerConfig.ConversationCache.class);
        when(config.conversationCache()).thenReturn(cacheCfg);
        when(cacheCfg.maxSize()).thenReturn(100L);
        when(cacheCfg.ttlSeconds()).thenReturn(60);
        caching = new CachingConversationManager();
        caching.delegate = delegate;
        caching.config = config;
        caching.init();
    }

    @Test
    @DisplayName("a miss loads from the delegate; a second lookup is served from the cache (one DB hit)")
    void loadOnMissThenServeFromCache() {
        when(delegate.findById("c1")).thenReturn(Uni.createFrom().item(CHAT));

        assertEquals(CHAT, caching.findById("c1").await().indefinitely());
        assertEquals(CHAT, caching.findById("c1").await().indefinitely());

        verify(delegate, times(1)).findById("c1");
    }

    @Test
    @DisplayName("an absent conversation is not negatively cached — it is reloaded each time")
    void absentNotCached() {
        when(delegate.findById("missing")).thenReturn(Uni.createFrom().nullItem());

        assertNull(caching.findById("missing").await().indefinitely());
        assertNull(caching.findById("missing").await().indefinitely());

        verify(delegate, times(2)).findById("missing");
    }

    @Test
    @DisplayName("hideFor is write-through: it evicts, so the next lookup reloads from the delegate")
    void hideForEvicts() {
        when(delegate.findById("c1")).thenReturn(Uni.createFrom().item(CHAT));
        when(delegate.hideFor("c1", "A")).thenReturn(Uni.createFrom().voidItem());

        caching.findById("c1").await().indefinitely();            // cached
        caching.hideFor("c1", "A").await().indefinitely();        // write-through evict
        caching.findById("c1").await().indefinitely();            // reload

        verify(delegate, times(2)).findById("c1");
    }

    @Test
    @DisplayName("setBlocked is write-through: it evicts, so the next lookup reloads from the delegate")
    void setBlockedEvicts() {
        when(delegate.findById("c1")).thenReturn(Uni.createFrom().item(CHAT));
        when(delegate.setBlocked("c1", "A", true)).thenReturn(Uni.createFrom().voidItem());

        caching.findById("c1").await().indefinitely();
        caching.setBlocked("c1", "A", true).await().indefinitely();
        caching.findById("c1").await().indefinitely();

        verify(delegate, times(2)).findById("c1");
    }

    @Test
    @DisplayName("advanceReadReceipts is write-through: it evicts the touched conversation (fresh peerLastReadId)")
    void advanceReadReceiptsEvicts() {
        when(delegate.findById("c1")).thenReturn(Uni.createFrom().item(CHAT));
        when(delegate.advanceReadReceipts(anyList())).thenReturn(Uni.createFrom().voidItem());

        caching.findById("c1").await().indefinitely();
        caching.advanceReadReceipts(List.of(new ReadReceiptMark("c1", "A", "M", "01READID0001")))
                .await().indefinitely();
        caching.findById("c1").await().indefinitely();

        verify(delegate, times(2)).findById("c1");
    }
}
