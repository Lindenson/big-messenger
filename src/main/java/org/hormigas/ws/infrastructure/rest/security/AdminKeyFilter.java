package org.hormigas.ws.infrastructure.rest.security;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.hormigas.ws.config.AdminConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Admin key filter — checks {@code X-Admin-Key} on endpoints annotated with {@link AdminOnly}.
 * Mirrors MasterProfile's AdminKeyFilter (the platform convention, ADR-006): admin/service access
 * is a shared-secret header check against {@code hormiga.admin.key}, not an {@code X-Role} gate.
 * The key flows straight through the Ory edge to the service. If the key is missing or wrong → 403.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class AdminKeyFilter implements ContainerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyFilter.class);
    static final String HEADER_ADMIN_KEY = "X-Admin-Key";

    // Key comes from the AdminConfig mapping class (project convention), sourced from
    // config/env (HORMIGA_ADMIN_KEY). Optional → an unset value never breaks startup;
    // absent/blank → fail-closed (every @AdminOnly request is rejected).
    @Inject
    AdminConfig adminConfig;

    @Context
    ResourceInfo resourceInfo;

    @Context
    UriInfo uriInfo;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!isAdminEndpoint(resourceInfo)) {
            return;
        }

        String provided = ctx.getHeaderString(HEADER_ADMIN_KEY);
        String configured = adminConfig.key().orElse("");

        if (provided == null || provided.isBlank()
                || configured.isBlank()
                || !provided.equals(configured)) {
            log.debug("Rejected admin request — bad/missing {}: {} {}",
                    HEADER_ADMIN_KEY, ctx.getMethod(),
                    uriInfo == null ? "?" : uriInfo.getPath());
            ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("Admin access required").build());
        }
    }

    private boolean isAdminEndpoint(ResourceInfo info) {
        if (info == null) return false;
        Method method = info.getResourceMethod();
        if (method != null && method.isAnnotationPresent(AdminOnly.class)) return true;
        Class<?> clazz = info.getResourceClass();
        return clazz != null && clazz.isAnnotationPresent(AdminOnly.class);
    }
}
