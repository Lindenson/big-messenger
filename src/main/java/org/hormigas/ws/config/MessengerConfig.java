package org.hormigas.ws.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "processing.messages")
public interface MessengerConfig {

    Inbound inbound();
    Outbound outbound();
    FastDelivery fastDelivery();
    Feedback feedback();
    Channel channel();
    Credits credits();
    Collector collector();
    Storage storage();
    Idempotent idempotent();
    ConversationCache conversationCache();
    ReadBatch readBatch();
    OfflinePush offlinePush();
    StreamRetry streamRetry();

    /**
     * Re-subscribe backoff shared by the long-lived reactive streams (inbound/outbound publishers and the
     * group-commit batchers): on a terminal stream failure they retry indefinitely with this exponential
     * backoff so a transient fault never permanently disables them.
     */
    interface StreamRetry {
        /** Initial re-subscribe delay. */
        @WithDefault("200")
        int minBackoffMs();

        /** Max re-subscribe delay (exponential cap). */
        @WithDefault("5000")
        int maxBackoffMs();
    }

    interface Inbound {
        int queueSize();

        /**
         * Max concurrent {@code routeIn} flows in flight (PARALLEL_BOUNDED merge). This is the inbound
         * throughput lever: μ ≈ concurrency / per-message-service-time (Little's law). The old unbounded
         * {@code transformToUniAndMerge} did not actually parallelize (effective concurrency ≈ 0), capping
         * inbound at ~10/s regardless of batch tuning; an explicit bounded merge (like the outbound path)
         * fixes it. Size it as {@code target_rate × service_time} (~32 covers 2000/s at ~10ms/msg).
         */
        @WithDefault("32")
        int deliveryConcurrency();

        PersistBatch persistBatch();

        /**
         * Group-commit of the inbound persist (plan B). The reactive inbound pipeline runs in
         * PARALLEL mode so many {@code routeIn} flows are in flight at once; this accumulator
         * coalesces their {@code history+outbox} writes into one transaction per batch, and runs
         * up to {@code maxConcurrentBatches} such transactions concurrently to fill the otherwise
         * ~95%-idle DB pool. Defaults sized for a 20-connection pool (see load-test findings R2).
         */
        interface PersistBatch {
            /** Max messages coalesced into a single transaction. */
            @WithDefault("64")
            int maxSize();

            /** Max time (ms) a partial batch waits before flushing — bounds the added latency. */
            @WithDefault("5")
            int lingerMs();

            /** Max batch transactions in flight at once (keep ≤ DB pool size). */
            @WithDefault("8")
            int maxConcurrentBatches();
        }
    }

    interface Outbound {
        int batchSize();
        int queueSize();
        String pollingMs();

        /** Max concurrent WS deliveries (PARALLEL_BOUNDED). ↑ raises delivery throughput (was serial);
         * keep ≤ WS/CPU headroom. NOTE: bounded merge does not preserve per-conversation order — a
         * keyed variant is planned. */
        @WithDefault("16")
        int deliveryConcurrency();
    }

    /**
     * Backpressure settings for the inbound live-delivery FAST PATH — the dedicated publisher the
     * {@code AsyncHandoffStage} emits into so recipient delivery runs on its OWN subscription/threads,
     * decoupled from inbound persist. Mirrors the outbound publisher (bounded-parallel + a queue gate);
     * the durable outbox poller is the catch-up path if this fast path sheds under load.
     */
    interface FastDelivery {
        /** Fast-path backpressure gate: over this many in-flight the hand-off sheds (poller still delivers). */
        @WithDefault("5000")
        int queueSize();

        /** Max concurrent WS deliveries on the fast path (PARALLEL_BOUNDED). */
        @WithDefault("32")
        int deliveryConcurrency();
    }

    interface Feedback {
        int additionalMs();
        double adjustmentFactor();
        double recoveryFactor();
        int maxMs();
    }

    interface Channel {
        boolean retry();
        int minBackoffMs();
        int maxBackoffMs();
        int maxRetries();
    }

    interface Credits {
        int maxValue();
        int refillRatePerS();
    }

    interface Collector {
        String everyS();
    }

    interface Storage {
        String service();
    }

    /**
     * In-process L1 conversation cache (Phase 2, 4.1). Declared here so the strict
     * {@code processing.messages} mapping accepts these keys; {@code CachingConversationManager}
     * reads the same canonical keys directly. Defaults match that adapter's.
     */
    interface ConversationCache {
        @WithDefault("100000")
        long maxSize();

        @WithDefault("60")
        int ttlSeconds();
    }

    /**
     * Group-commit of read-status writes (READ_IN), mirroring the inbound persist batcher: READ receipts
     * hit the DB only in batches, never row-per-event. Same shape/knobs as {@code inbound.persist-batch}.
     */
    interface ReadBatch {
        @WithDefault("64")
        int maxSize();

        @WithDefault("10")
        int lingerMs();

        @WithDefault("4")
        int maxConcurrentBatches();
    }

    interface Idempotent {
        int ttlSeconds();
    }

    /**
     * Offline-recipient notification publisher (feeds the Go notification service over Kafka topic
     * {@code chat.offline.notify}). Emitted when a durable chat/system message can't be delivered live
     * because the recipient has no open WS session. Coalescing knobs mirror {@code read-batch}: a burst
     * of undelivered messages to one offline peer in one conversation collapses to a single push event
     * per short window. Best-effort — a shed/failed publish never affects the durable outbox delivery.
     */
    interface OfflinePush {
        /** Master switch. When false the facade is a pure no-op (no events emitted, no batcher wired). */
        @WithDefault("true")
        boolean enabled();

        /** Kafka topic the offline events are published to (single source of truth; set per-record). */
        @WithDefault("chat.offline.notify")
        String topic();

        /** Max observations coalesced into one timed window. */
        @WithDefault("64")
        int maxSize();

        /** Max time (ms) a partial window waits before flushing — the coalescing window length. */
        @WithDefault("10")
        int lingerMs();

        /** Max coalesced batches published concurrently. */
        @WithDefault("4")
        int maxConcurrentBatches();
    }
}
