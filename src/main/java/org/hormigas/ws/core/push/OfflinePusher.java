package org.hormigas.ws.core.push;

import org.hormigas.ws.domain.push.OfflineEvent;

import java.time.Duration;

/**
 * Driving interface for the offline-recipient notification buffer — the seam the outbound delivery adapter
 * ({@code Deliverer}) feeds "could-not-deliver-live" observations into. Kept as an interface (like
 * {@code AsyncPresence} in {@code core.presence} and {@code ReadReceiptSink} in {@code core.receipt}) so the
 * infrastructure adapter depends on this abstraction, not on the concrete {@link OfflinePusherBatcher}.
 *
 * <p><b>Fire-and-forget.</b> {@link #push} never blocks and never throws into the delivery path: if
 * the coalescing buffer is full (or closed during shutdown) the observation is shed and counted, never
 * back-pressured. A dropped notification is harmless — the message itself is already durably held in the
 * outbox and redelivered on reconnect; the offline push is a best-effort courtesy.
 */
public interface OfflinePusher {

    /** Feed one offline-recipient observation into the coalescing window. Non-blocking, fire-and-forget. */
    void push(OfflineEvent event);

    /** Graceful shutdown: flush the in-flight coalescing window and block until published (or timeout). */
    void drain(Duration timeout);
}
