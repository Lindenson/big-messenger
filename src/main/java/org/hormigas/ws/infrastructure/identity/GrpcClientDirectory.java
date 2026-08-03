package org.hormigas.ws.infrastructure.identity;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.grpc.GrpcClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import org.hormigas.ids.grpc.v1.GetUserRequest;
import org.hormigas.ids.grpc.v1.ListUsersRequest;
import org.hormigas.ids.grpc.v1.MutinyIdentityDirectoryGrpc.MutinyIdentityDirectoryStub;
import org.hormigas.ids.grpc.v1.Role;
import org.hormigas.ids.grpc.v1.User;

import org.hormigas.ws.domain.credentials.ClientData;
import org.hormigas.ws.ports.identity.ClientDirectory;

import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link ClientDirectory} backed by the Identity Directory Service (KratosGate
 * IDS) over gRPC. Replaces {@link StubClientDirectory} in the {@code prod} build profile;
 * the core is unchanged because the port contract is identical.
 *
 * <p>Adapted from KratosGate's reference client kit
 * ({@code clients/messenger/example/GrpcClientDirectory.java}). The Mutiny stub is generated
 * at build time from {@code src/main/proto/ids/v1/identity.proto} by {@code quarkus-grpc};
 * the client is configured under {@code quarkus.grpc.clients.ids.*}.
 */
@ApplicationScoped
@IfBuildProfile("prod")
public class GrpcClientDirectory implements ClientDirectory {

    private static final int PAGE_SIZE = 200;

    @GrpcClient("ids")
    MutinyIdentityDirectoryStub directory;

    @Override
    public Uni<ClientData> findById(String id) {
        return directory.getUser(GetUserRequest.newBuilder().setId(id).build())
                .map(resp -> toClientData(resp.getUser()))
                // Per the port contract: not found emits null rather than failing.
                .onFailure(GrpcClientDirectory::isNotFound).recoverWithItem((ClientData) null);
    }

    @Override
    public Uni<List<ClientData>> listClients() {
        return fetchPage("", new ArrayList<>());
    }

    /** Reactively pages through ListUsers, accumulating results. */
    private Uni<List<ClientData>> fetchPage(String token, List<ClientData> acc) {
        var req = ListUsersRequest.newBuilder().setPageSize(PAGE_SIZE).setPageToken(token).build();
        return directory.listUsers(req).flatMap(resp -> {
            for (User u : resp.getUsersList()) {
                acc.add(toClientData(u));
            }
            String next = resp.getNextPageToken();
            if (next == null || next.isEmpty()) {
                return Uni.createFrom().item(acc);
            }
            return fetchPage(next, acc);
        });
    }

    private static ClientData toClientData(User u) {
        return new ClientData(u.getId(), displayName(u), role(u.getRole()), u.getEmail());
    }

    private static String displayName(User u) {
        if (!u.getDisplayName().isBlank()) return u.getDisplayName();
        String full = (u.getFirstName() + " " + u.getLastName()).trim();
        if (!full.isBlank()) return full;
        return u.getEmail();
    }

    /** Maps the wire enum onto the platform role string; unspecified -> null. */
    private static String role(Role r) {
        return switch (r) {
            case ROLE_MASTER -> "MASTER";
            case ROLE_CLIENT -> "CLIENT";
            case ROLE_ADMIN -> "ADMIN";
            case ROLE_USER -> "USER";
            default -> null;
        };
    }

    private static boolean isNotFound(Throwable t) {
        return t instanceof StatusRuntimeException e
                && e.getStatus().getCode() == Status.Code.NOT_FOUND;
    }
}
