package org.hormigas.ws.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Admin/service access secret — the {@code X-Admin-Key} checked by {@code AdminKeyFilter} on
 * {@code @AdminOnly} endpoints (platform convention, ADR-006). Sourced from config/env
 * ({@code HORMIGA_ADMIN_KEY}), never hardcoded and never committed to {@code application.yaml}.
 * {@link Optional} so an unset value does NOT break startup; absent or blank → admin endpoints are
 * fail-closed (every {@code @AdminOnly} request is rejected).
 *
 * <p>A small {@code @ApplicationScoped} config bean (rather than a {@code @ConfigMapping} interface
 * like the other config classes): a standalone mapping for this single key did not register in this
 * app's build (SmallRye {@code SRCFG00027}), so the value is read via {@code @ConfigProperty} here.
 * The effect is the same — the key is declared in one place and injected wherever it is needed.
 */
@ApplicationScoped
public class AdminConfig {

    @ConfigProperty(name = "hormiga.admin.key")
    Optional<String> key;

    /** The shared admin key; empty when unset (blank/absent → admin endpoints fail-closed). */
    public Optional<String> key() {
        return key;
    }
}
