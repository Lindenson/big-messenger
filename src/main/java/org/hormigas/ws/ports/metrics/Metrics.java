package org.hormigas.ws.ports.metrics;

import org.hormigas.hl.batch.metrics.BatchMetrics;

import java.util.function.DoubleConsumer;

/**
 * Metrics seam for the application layer. Core/domain speak metric intents through this port; the wiring
 * edge backs it with Micrometer — so a use-case class (e.g. a batch facade) never depends on
 * {@code io.micrometer} directly. Mirrors how the hl mechanisms take a {@code PollerMetrics}/{@code BatchMetrics}
 * seam and let the app supply the backend.
 */
public interface Metrics {

    /** A {@link BatchMetrics} for the given metric prefix (backs the hl batch mechanism's meters). */
    BatchMetrics batch(String prefix);

    /** A single counter, returned as an increment handle — {@code accept(n)} adds {@code n}. */
    DoubleConsumer counter(String name, String description);
}
