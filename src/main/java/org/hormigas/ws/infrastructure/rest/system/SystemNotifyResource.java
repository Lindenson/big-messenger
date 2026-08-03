package org.hormigas.ws.infrastructure.rest.system;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.hormigas.ws.core.notify.Notices;
import org.hormigas.ws.infrastructure.rest.security.AdminOnly;
import org.hormigas.ws.infrastructure.security.IdentityHeaders;

import java.util.Map;

/**
 * REST driving adapter for must-arrive system notices (Strategy C, ADR-014) — the dual-driven REST
 * side of {@link Notices}. Restricted to ADMIN/SERVICE callers (system notices are
 * server-originated, never client-initiated). Also the manual/ops + e2e trigger for the C path.
 */
@Path("/api/system/notify")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SystemNotifyResource {

    @Inject
    Notices notifier;

    public record NotifyRequest(String recipientId, String kind, String body,
                                Map<String, String> meta, String conversationId) {}

    @POST
    @AdminOnly
    public Uni<Response> notify(
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email,
            NotifyRequest req) {
        // Access gated by AdminKeyFilter (@AdminOnly → X-Admin-Key), platform convention (ADR-006).
        if (req == null || req.recipientId() == null || req.recipientId().isBlank()) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).build());
        }
        return notifier.notify(req.recipientId(), req.kind(), req.body(), req.meta(), req.conversationId())
                .replaceWith(Response.status(Response.Status.ACCEPTED).entity(Map.of("status", "queued")).build());
    }
}
