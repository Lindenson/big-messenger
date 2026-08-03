package hormiga.ws;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** REST setup helper for the WS suite (create chats, trigger system notices, read history). */
public final class Rest {

    public static final String BASE = System.getProperty("e2e.baseUrl", "http://localhost:8080");
    public static final String WS_URL = System.getProperty("e2e.wsUrl", "ws://localhost:8080/ws");
    /** Shared admin secret checked by AdminKeyFilter on @AdminOnly endpoints (ADR-006); simulates the edge. */
    public static final String ADMIN_KEY = System.getProperty("admin.key", "test-admin-key-secret");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Rest() {}

    /** Ory identity headers (the gateway trust model — the app trusts these verbatim). */
    public static Map<String, String> hdr(String id, String role) {
        return Map.of(
                "X-User-Id", id,
                "X-User", role,
                "X-Role", role.toUpperCase(),
                "X-User-Email", id + "@test.eu");
    }

    /** Identity headers + the shared admin key — for @AdminOnly endpoints (POST /api/chats, /api/system/notify). */
    public static Map<String, String> adminHdr(String id, String role) {
        return Map.of(
                "X-User-Id", id,
                "X-User", role,
                "X-Role", role.toUpperCase(),
                "X-User-Email", id + "@test.eu",
                "X-Admin-Key", ADMIN_KEY);
    }

    public record Resp(int status, JsonNode body) {}

    public static Resp post(String path, String json, Map<String, String> headers) {
        return send("POST", path, json, headers);
    }

    public static Resp get(String path, Map<String, String> headers) {
        return send("GET", path, null, headers);
    }

    public static Resp delete(String path, Map<String, String> headers) {
        return send("DELETE", path, null, headers);
    }

    /** PUT raw bytes to an ABSOLUTE url (the presigned storage upload URL). Returns the HTTP status. */
    public static int put(String absoluteUrl, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(absoluteUrl))
                    .header("Content-Type", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new RuntimeException("PUT " + absoluteUrl + " failed", e);
        }
    }

    private static Resp send(String method, String path, String json, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            headers.forEach(b::header);
            b.method(method, json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = null;
            if (r.body() != null && !r.body().isBlank()) {
                // Some error responses (e.g. a 403 "Admin access required") are plain text, not JSON —
                // tolerate that so status-only assertions don't blow up on a parse failure.
                try {
                    body = WsTestClient.MAPPER.readTree(r.body());
                } catch (Exception ignore) {
                    body = null;
                }
            }
            return new Resp(r.statusCode(), body);
        } catch (Exception e) {
            throw new RuntimeException(method + " " + path + " failed", e);
        }
    }

    /** Create the (client, master) conversation and return its id. Chats are provisioned
     *  service-to-service / admin only (D4) — create with a SERVICE identity, not a participant. */
    public static String createChat(String clientId, String masterId) {
        Resp r = post("/api/chats",
                "{\"clientId\":\"" + clientId + "\",\"masterId\":\"" + masterId + "\",\"metadata\":{}}",
                adminHdr("platform-svc", "SERVICE"));
        if (r.status() != 201 && r.status() != 200) {
            throw new IllegalStateException("createChat failed: HTTP " + r.status());
        }
        return r.body().get("id").asText();
    }
}
