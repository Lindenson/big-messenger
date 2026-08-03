package org.hormigas.ws.core.receipt;

import org.hormigas.ws.domain.conversation.ReadReceiptMark;

import java.time.Duration;

/**
 * Driving interface for the read-receipt write-behind buffer — the seam the REST adapter and the READ
 * pipeline stage feed authorized read observations into. Kept as an interface (like {@code Chats} in
 * {@code core.conversation}) so infrastructure adapters depend on this abstraction, not on the concrete
 * {@link ReadReceiptBatcher} implementation. Fire-and-forget: a soft receipt self-heals on the next READ_IN.
 */
public interface ReadReceiptSink {

    /** Feed one authorized read observation into the coalescing window. Non-blocking, fire-and-forget. */
    void observe(ReadReceiptMark mark);

    /** Graceful shutdown: flush the in-flight window and block until sinked (or timeout). */
    void drain(Duration timeout);
}
