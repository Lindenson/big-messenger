package org.hormigas.ws.infrastructure.websocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics + overload-notice policy companion for {@link WebsocketService} — keeps the transport adapter free
 * of Micrometer wiring (mirrors the {@code *PublisherMetrics} companions). Owns the ingress flood/overload
 * counters and the coarse rate-limit that stops the server burning CPU notifying every shed message under
 * overload.
 */
@Startup // register the meters at boot so the dashboard has them even before the first credit/overload event
@ApplicationScoped
public class WebSocketMetrics {

    /** Under overload the inbound queue sheds ~thousands/s; sending a SERVICE_OUT back per drop burns CPU
     *  exactly when saturated. Cap total notices/s — senders still get a retry signal, overhead stays bounded. */
    private static final int MAX_OVERLOAD_NOTICES_PER_SEC = 50;

    @Inject
    MeterRegistry registry;

    private Counter creditsExhausted;
    private Counter overloadNoticesSuppressed;
    private Counter connects;
    private Counter disconnects;
    private Counter connClosed;
    private Counter connErrors;

    private final AtomicLong noticeWindowSec = new AtomicLong();
    private final AtomicInteger noticesThisSec = new AtomicInteger();

    @PostConstruct
    void init() {
        creditsExhausted = Counter.builder("messenger.credits.exhausted")
                .description("Inbound frames rejected by the per-connection credit gate (flood/abuse guard)")
                .register(registry);
        overloadNoticesSuppressed = Counter.builder("messenger.overload.notices.suppressed")
                .description("Overload SERVICE_OUT notices skipped by the rate limit (drop still counted in incoming.dropped)")
                .register(registry);
        // WS connection lifecycle rates — sessions_registered is a live gauge; these expose the CHURN
        // (connects/disconnects/errors per second) that a gauge hides. connects−disconnects ≈ the gauge.
        connects = Counter.builder("messenger.ws.connects")
                .description("WebSocket sessions opened (joined)").register(registry);
        disconnects = Counter.builder("messenger.ws.disconnects")
                .description("WebSocket sessions closed (onClose)").register(registry);
        connClosed = Counter.builder("messenger.ws.errors").tag("kind", "closed")
                .description("WebSocket connections dropped abruptly (channel closed / peer reset)").register(registry);
        connErrors = Counter.builder("messenger.ws.errors").tag("kind", "error")
                .description("WebSocket connection errors (non-close)").register(registry);
    }

    /** A frame was rejected by the per-connection credit gate. */
    public void recordCreditsExhausted() {
        creditsExhausted.increment();
    }

    /** Count an accepted inbound frame by its type — shows the ingress mix (CHAT_IN vs CHAT_ACK vs READ_IN…);
     *  e.g. under prompt-ACK, CHAT_ACK ≈ CHAT_IN, so inbound routeIn carries ~2× the chat rate. Registry
     *  caches the per-type counter, so this is hot-path safe. */
    public void recordReceived(String type) {
        registry.counter("messenger.inbound.received", "type", type).increment();
    }

    /** A WebSocket session opened (successful join). */
    public void recordConnect() {
        connects.increment();
    }

    /** A WebSocket session closed normally (onClose). */
    public void recordDisconnect() {
        disconnects.increment();
    }

    /** A WebSocket connection dropped abruptly ({@code closed=true}) or errored ({@code closed=false}). */
    public void recordConnectionError(boolean closed) {
        (closed ? connClosed : connErrors).increment();
    }

    /**
     * Coarse global token gate for overload notices: at most {@link #MAX_OVERLOAD_NOTICES_PER_SEC} per second.
     * Returns {@code true} if a notice may be sent; when it returns {@code false} the suppressed counter is
     * incremented so the shed notices stay observable.
     */
    public boolean allowOverloadNotice() {
        long sec = System.currentTimeMillis() / 1000L;
        long w = noticeWindowSec.get();
        if (sec != w && noticeWindowSec.compareAndSet(w, sec)) {
            noticesThisSec.set(0);
        }
        if (noticesThisSec.incrementAndGet() <= MAX_OVERLOAD_NOTICES_PER_SEC) {
            return true;
        }
        overloadNoticesSuppressed.increment();
        return false;
    }
}
