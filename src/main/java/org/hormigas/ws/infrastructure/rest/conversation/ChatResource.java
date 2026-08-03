package org.hormigas.ws.infrastructure.rest.conversation;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.hormigas.ws.core.conversation.Chats;
import org.hormigas.ws.core.receipt.ReadReceiptSink;
import org.hormigas.ws.domain.conversation.result.Guarded;
import org.hormigas.ws.domain.conversation.result.GuardedOutcome;
import org.hormigas.ws.infrastructure.rest.history.security.TokenVerifier;
import org.hormigas.ws.infrastructure.rest.security.AdminOnly;
import org.hormigas.ws.infrastructure.security.IdentityHeaders;

import java.util.Map;

/**
 * REST adapter over the conversation use cases. Every endpoint is thin: it authenticates the Ory
 * identity headers, delegates to the {@link Chats} core use case, and renders the domain
 * result to HTTP. Membership/authorization and all business logic live in {@code core} (the
 * {@code guardedRead} guard), never here — this resource only adapts HTTP to the use cases and back.
 */
@Path("/api/chats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    @Inject
    Chats conversations;

    @Inject
    ReadReceiptSink buffer;

    @Inject
    TokenVerifier auth;

    public record CreateChatRequest(String clientId, String masterId, Map<String, String> metadata) {}

    public record FreezeRequest(String orderId) {}

    /**
     * Create a chat (idempotent on the pair). <b>Service-to-service / admin only</b> (D4): chats are
     * provisioned by the platform — a master-interested / new-order event (Kafka {@code order.events}),
     * or an operator — never by an end client. Mirrors {@code /api/system/notify}: requires the Ory
     * edge to inject {@code X-Role} ∈ {ADMIN, SERVICE}.
     */
    @POST
    @AdminOnly
    public Uni<Response> create(
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email,
            CreateChatRequest req) {

        // Access is gated by AdminKeyFilter (@AdminOnly → X-Admin-Key), the platform convention
        // (ADR-006 / MasterProfile). No X-Role/identity gate: chat provisioning is an admin/service
        // op authorised by the shared admin secret, which flows straight through the Ory edge.
        if (req == null || req.clientId() == null || req.masterId() == null) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).build());
        }
        return conversations.createChat(req.clientId(), req.masterId(), req.metadata())
                .map(result -> {
                    // #3 — a blocked pair cannot be provisioned; surface a 409 instead of silently
                    // returning a chat that then can't message (the block would be silently overridden).
                    if (result.blocked()) {
                        return Response.status(Response.Status.CONFLICT).entity(result.conversation()).build();
                    }
                    return Response
                            .status(result.created() ? Response.Status.CREATED : Response.Status.OK)
                            .entity(result.conversation())
                            .build();
                });
    }

    @GET
    public Uni<Response> list(
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {

        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) {
            return unauthorized();
        }
        return conversations.listChats(caller.get().id())
                .map(list -> Response.ok(list).build());
    }

    @GET
    @Path("/{chatId}/messages")
    public Uni<Response> messages(
            @PathParam("chatId") String chatId,
            @QueryParam("since") String since,
            @QueryParam("before") String before,
            @QueryParam("limit") Integer limit,
            @QueryParam("includeDeleted") @DefaultValue("false") boolean includeDeleted,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {

        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) {
            return unauthorized();
        }
        // Conversation-scoped, cursor-paginated history (UC-U50). Guard + clamping live in core.
        //  - default / ?before=<id>  → BACKWARD scroll-up: the newest page older than `before` (none = latest).
        //  - ?since=<id>             → FORWARD catch-up: messages newer than the cursor (reconnect sync).
        // includeDeleted=true returns the caller's deleted (pre-watermark) history too ("show deleted").
        return conversations.history(chatId, caller.get().id(), since, before, limit, includeDeleted)
                .map(this::guardedToResponse);
    }

    @DELETE
    @Path("/{chatId}")
    public Uni<Response> softDelete(
            @PathParam("chatId") String chatId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        return conversations.hide(chatId, caller.get().id()).map(this::toResponse);
    }

    @POST
    @Path("/{chatId}/block")
    public Uni<Response> block(
            @PathParam("chatId") String chatId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        return conversations.setBlocked(chatId, caller.get().id(), true).map(this::toResponse);
    }

    @DELETE
    @Path("/{chatId}/block")
    public Uni<Response> unblock(
            @PathParam("chatId") String chatId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        return conversations.setBlocked(chatId, caller.get().id(), false).map(this::toResponse);
    }

    /**
     * Delete a message by the SERVER id (path). A message is addressable by either its server id or the
     * client-original id (see {@link #deleteMessageByIds}); this path form passes whichever id the client
     * put in the path and the store matches either column.
     */
    @DELETE
    @Path("/{chatId}/messages/{messageId}")
    public Uni<Response> deleteMessage(
            @PathParam("chatId") String chatId,
            @PathParam("messageId") String messageId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {
        return doDelete(chatId, messageId, userId, userName, role, email);
    }

    /**
     * Delete a message by its TYPED ids (collection form): the server id ({@code backendId}) and/or the
     * client-original id ({@code clientMessageId}) the phone holds. The phone generates its own id and, until
     * the server ACK arrives, knows only that one — so it may send the client id alone (delete before ACK),
     * the server id alone (after ACK), or both. When BOTH are sent the match is <b>strict</b> (exact row);
     * when one is sent, by that id. At least one is required (400 otherwise).
     */
    @DELETE
    @Path("/{chatId}/messages")
    public Uni<Response> deleteMessageByIds(
            @PathParam("chatId") String chatId,
            @QueryParam("backendId") String backendId,
            @QueryParam("clientMessageId") String clientMessageId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {
        boolean hasServer = backendId != null && !backendId.isBlank();
        boolean hasClient = clientMessageId != null && !clientMessageId.isBlank();
        if (!hasServer && !hasClient) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).build());
        }
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        return renderDelete(conversations.deleteMessage(chatId, caller.get().id(), backendId, clientMessageId));
    }

    private Uni<Response> doDelete(String chatId, String messageId,
                                   String userId, String userName, String role, String email) {
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        return renderDelete(conversations.deleteMessage(chatId, caller.get().id(), messageId));
    }

    /** Render a guarded delete outcome to HTTP (shared by the path and typed-ids forms). */
    private Uni<Response> renderDelete(Uni<Guarded<org.hormigas.ws.ports.message.MessageModeration.DeleteOutcome>> result) {
        return result.map(g -> switch (g.outcome()) {
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
            case FORBIDDEN -> Response.status(Response.Status.FORBIDDEN).build();
            case OK -> switch (g.value()) {
                case DELETED -> Response.noContent().build();
                case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
                case FROZEN -> Response.status(Response.Status.CONFLICT).build(); // 409: frozen, immutable
                case FORBIDDEN -> Response.status(Response.Status.FORBIDDEN).build(); // 403: not the message owner
            };
        });
    }

    @POST
    @Path("/{chatId}/freeze")
    public Uni<Response> freeze(
            @PathParam("chatId") String chatId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email,
            FreezeRequest req) {
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        // Freeze is message-level, scoped by orderId (UC-U22) — there is no chat-wide freeze.
        if (req == null || req.orderId() == null || req.orderId().isBlank()) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).build());
        }
        return conversations.freezeByOrder(chatId, caller.get().id(), req.orderId())
                .map(g -> switch (g.outcome()) {
                    case OK -> Response.ok(Map.of("frozen", g.value())).build();
                    case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
                    case FORBIDDEN -> Response.status(Response.Status.FORBIDDEN).build();
                });
    }

    @POST
    @Path("/{chatId}/read")
    public Uni<Response> markRead(
            @PathParam("chatId") String chatId,
            @QueryParam("lastReadId") String lastReadId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        // REST fallback for the READ_IN WebSocket event (UC-U14): the caller reports read-up-to lastReadId
        // (client-supplied ULID). The use-case resolves the authorized mark; this adapter feeds it into the
        // pipeline's coalescing buffer (REST ingress — mirrors WebsocketService feeding InboundPublisher).
        return conversations.resolveReadMark(chatId, caller.get().id(), lastReadId)
                .map(g -> {
                    if (g.value() != null) {
                        buffer.observe(g.value());
                    }
                    return switch (g.outcome()) {
                        case OK -> Response.ok(Map.of("read", true)).build();
                        case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
                        case FORBIDDEN -> Response.status(Response.Status.FORBIDDEN).build();
                    };
                });
    }

    @GET
    @Path("/{chatId}/receipts")
    public Uni<Response> receipts(
            @PathParam("chatId") String chatId,
            @HeaderParam(IdentityHeaders.USER_ID) String userId,
            @HeaderParam(IdentityHeaders.USER_NAME) String userName,
            @HeaderParam(IdentityHeaders.USER_ROLE) String role,
            @HeaderParam(IdentityHeaders.USER_EMAIL) String email) {
        var caller = auth.fromHeaders(userId, userName, role, email);
        if (caller.isEmpty()) return unauthorized();
        return conversations.receipts(chatId, caller.get().id()).map(this::guardedToResponse);
    }

    /** Render a membership-guarded read result to HTTP: OK→200 body, NOT_FOUND→404, FORBIDDEN→403. */
    private <T> Response guardedToResponse(Guarded<T> g) {
        return switch (g.outcome()) {
            case OK -> Response.ok(g.value()).build();
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
            case FORBIDDEN -> Response.status(Response.Status.FORBIDDEN).build();
        };
    }

    private Response toResponse(GuardedOutcome outcome) {
        return switch (outcome) {
            case OK -> Response.noContent().build();
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
            case FORBIDDEN -> Response.status(Response.Status.FORBIDDEN).build();
        };
    }

    private Uni<Response> unauthorized() {
        return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED).build());
    }
}
