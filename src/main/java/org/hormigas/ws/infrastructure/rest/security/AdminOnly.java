package org.hormigas.ws.infrastructure.rest.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JAX-RS resource class or method as requiring admin/service privileges.
 * Checked by {@link AdminKeyFilter} — requires the {@code X-Admin-Key} header to match the
 * {@code hormiga.admin.key} config property. Mirrors the platform convention used by the other
 * Hormigas services (e.g. MasterProfile), so admin access is a single shared-key check that flows
 * straight through the Ory edge, rather than an {@code X-Role} gate the edge never emits for admins.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOnly {}
