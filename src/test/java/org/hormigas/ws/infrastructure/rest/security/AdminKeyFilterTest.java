package org.hormigas.ws.infrastructure.rest.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.hormigas.ws.config.AdminConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AdminKeyFilter — X-Admin-Key gate on @AdminOnly endpoints")
class AdminKeyFilterTest {

    // reflection fixtures
    @AdminOnly
    static class Guarded { public void m() {} }
    static class Open { public void m() {} }

    private AdminKeyFilter filter;
    private ContainerRequestContext ctx;
    private ResourceInfo info;

    @BeforeEach
    void setUp() {
        filter = new AdminKeyFilter();
        AdminConfig cfg = mock(AdminConfig.class);
        when(cfg.key()).thenReturn(Optional.of("s3cret"));
        filter.adminConfig = cfg;
        ctx = mock(ContainerRequestContext.class);
        info = mock(ResourceInfo.class);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn("/api/admin/chats");
        filter.uriInfo = uri;
        filter.resourceInfo = info;
    }

    private void onGuarded() throws Exception {
        when(info.getResourceMethod()).thenReturn(Guarded.class.getMethod("m"));
        when(info.getResourceClass()).thenReturn((Class) Guarded.class);
    }

    private void onOpen() throws Exception {
        when(info.getResourceMethod()).thenReturn(Open.class.getMethod("m"));
        when(info.getResourceClass()).thenReturn((Class) Open.class);
    }

    private int abortedStatus() {
        ArgumentCaptor<Response> c = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(c.capture());
        return c.getValue().getStatus();
    }

    @Test
    @DisplayName("non-@AdminOnly endpoint → passes untouched (no header read)")
    void openPasses() throws Exception {
        onOpen();
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
        verify(ctx, never()).getHeaderString(anyString());
    }

    @Test
    @DisplayName("@AdminOnly + correct X-Admin-Key → passes")
    void correctKeyPasses() throws Exception {
        onGuarded();
        when(ctx.getHeaderString("X-Admin-Key")).thenReturn("s3cret");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("@AdminOnly + wrong key → 403")
    void wrongKeyForbidden() throws Exception {
        onGuarded();
        when(ctx.getHeaderString("X-Admin-Key")).thenReturn("nope");
        filter.filter(ctx);
        assertEquals(403, abortedStatus());
    }

    @Test
    @DisplayName("@AdminOnly + missing key → 403")
    void missingKeyForbidden() throws Exception {
        onGuarded();
        when(ctx.getHeaderString("X-Admin-Key")).thenReturn(null);
        filter.filter(ctx);
        assertEquals(403, abortedStatus());
    }

    @Test
    @DisplayName("fail-closed: unset/blank configured key → 403 even with a header")
    void unsetConfigForbidden() throws Exception {
        AdminConfig cfg = mock(AdminConfig.class);
        when(cfg.key()).thenReturn(Optional.empty());   // HORMIGA_ADMIN_KEY not configured
        filter.adminConfig = cfg;
        onGuarded();
        when(ctx.getHeaderString("X-Admin-Key")).thenReturn("anything");
        filter.filter(ctx);
        assertEquals(403, abortedStatus());
    }
}
