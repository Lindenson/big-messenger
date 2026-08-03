package org.hormigas.ws.infrastructure.rest.mdc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.hormigas.ws.infrastructure.security.IdentityHeaders;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Puts correlation context into the MDC for every REST request so log lines carry {@code userId} (the Ory
 * {@code X-User-Id}) and a per-request {@code requestId} (echoed back in the {@code X-Request-Id} response
 * header for client-side correlation). The MDC is propagated across the reactive endpoint via
 * {@code quarkus-smallrye-context-propagation}, then cleared in the response filter so nothing leaks to
 * the next request on a pooled thread.
 */
@Provider
@ApplicationScoped
public class MdcRequestFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public void filter(ContainerRequestContext ctx) {
        String userId = ctx.getHeaderString(IdentityHeaders.USER_ID);
        if (userId != null && !userId.isBlank()) {
            MDC.put("userId", userId);
        }
        // Reuse an inbound request id if the edge set one; otherwise mint a fresh, short one.
        String requestId = ctx.getHeaderString(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", requestId);
        ctx.setProperty("requestId", requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestCtx, ContainerResponseContext responseCtx) {
        Object requestId = requestCtx.getProperty("requestId");
        if (requestId != null) {
            responseCtx.getHeaders().putSingle(REQUEST_ID_HEADER, requestId);
        }
        MDC.clear();
    }
}
