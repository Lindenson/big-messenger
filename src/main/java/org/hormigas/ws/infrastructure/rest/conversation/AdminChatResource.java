package org.hormigas.ws.infrastructure.rest.conversation;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.hormigas.ws.core.conversation.AdminChats;
import org.hormigas.ws.domain.conversation.ChatQuery;
import org.hormigas.ws.domain.conversation.ChatSort;
import org.hormigas.ws.domain.conversation.Conversation;
import org.hormigas.ws.infrastructure.rest.security.AdminOnly;
import org.hormigas.ws.infrastructure.security.IdentityHeaders;

import java.time.Instant;
import java.util.List;

/**
 * Admin chat console (scope B): platform-wide listing with filters/sort/paging, totals and stats.
 * <b>ADMIN only</b> — gated by {@link AdminOnly} → {@code X-Admin-Key} (platform convention, ADR-006),
 * not an {@code X-Role} gate the Ory edge never emits for admins. Thin adapter: it builds a
 * {@link ChatQuery}, delegates to the {@link AdminChats} core use case (which reads unfiltered —
 * an admin sees every chat regardless of per-side delete/block), and renders to HTTP.
 */
@Path("/api/admin/chats")
@Produces(MediaType.APPLICATION_JSON)
@org.hormigas.ws.infrastructure.rest.ratelimit.RateLimit("admin")
@AdminOnly
public class AdminChatResource {

    @Inject
    AdminChats adminChats;

    /** A page of admin results plus the total count for pagination. */
    public record ChatPage(List<Conversation> items, long total, int limit, int offset) {}

    @GET
    public Uni<Response> list(
            @QueryParam("participant") String participant,
            @QueryParam("conversationId") String conversationId,
            @QueryParam("blocked") @DefaultValue("false") boolean blocked,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("sort") String sort,
            @QueryParam("limit") @DefaultValue("0") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {

        // Access gated by AdminKeyFilter (@AdminOnly → X-Admin-Key), platform convention (ADR-006).

        ChatQuery query;
        try {
            query = new ChatQuery(blank(participant), blank(conversationId), blocked,
                    parseInstant(from), parseInstant(to), parseSort(sort), limit, offset);
        } catch (IllegalArgumentException badParam) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                    .entity(badParam.getMessage()).build());
        }

        return Uni.combine().all().unis(adminChats.list(query), adminChats.count(query))
                .asTuple()
                .map(t -> Response.ok(new ChatPage(t.getItem1(), t.getItem2(),
                        query.limit() <= 0 ? t.getItem1().size() : query.limit(), query.offset())).build());
    }

    @GET
    @Path("/stats")
    public Uni<Response> stats(
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {

        // Access gated by AdminKeyFilter (@AdminOnly → X-Admin-Key), platform convention (ADR-006).
        return adminChats.stats().map(s -> Response.ok(s).build());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid timestamp (expected ISO-8601 instant): " + s);
        }
    }

    private static ChatSort parseSort(String s) {
        if (s == null || s.isBlank()) return null; // core defaults to UPDATED_DESC
        try {
            return ChatSort.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid sort: " + s
                    + " (one of created_asc, created_desc, updated_asc, updated_desc)");
        }
    }
}
