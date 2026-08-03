package org.hormigas.ws.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Graceful-shutdown config. On {@code ShutdownEvent} the service closes the inbound door and drains the
 * group-commit batchers, blocking up to {@link #drainTimeoutMs()} for in-flight batches to commit.
 */
@ConfigMapping(prefix = "processing.shutdown")
public interface ShutdownConfig {

    /** Max time to wait for the batchers to flush in-flight work before proceeding. Un-committed work at
     *  timeout is not acked, so its sender redelivers. ↑ fewer redeliveries on a busy stop, ↓ faster stop. */
    @WithDefault("5000")
    int drainTimeoutMs();
}
