package org.domainforge.gateway.infrastructure.routing;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路由配置只接受受限结构；任何一项非法都使整组不可应用。
 */
class GatewaySettingsTest {
    private static final RateLimitSettings LIMIT = new RateLimitSettings(20, 40);

    private static RouteSettings route(String id, List<String> paths, List<String> anonymous) {
        return new RouteSettings(id, "svc", paths, anonymous, null);
    }

    // 越过 /api/、指向 internal 段、含 .. 或空段、无法解析的路径都被拒绝。
    @Test
    void rejectsUnsafePaths() {
        for (String path : List.of("/internal/**", "/api/internal/**", "/api/x/../internal", "/api//x", "/actuator/env",
                "/api/{", "api/x"))
            assertThrows(IllegalArgumentException.class, () -> route("r", List.of(path), null), path);
        assertDoesNotThrow(() -> route("r", List.of("/api/internal-notes/**"), null));
    }

    // 匿名项必须是确切路径、受支持的方法，并落在本路由的路径内。
    @Test
    void anonymousEntriesStayInsideRoute() {
        var route = route("iam", List.of("/api/**"), List.of("POST /api/auth/login"));
        assertTrue(route.allowsAnonymous("POST", PathContainer.parsePath("/api/auth/login")));
        assertFalse(route.allowsAnonymous("GET", PathContainer.parsePath("/api/auth/login")));
        assertFalse(route.allowsAnonymous("POST", PathContainer.parsePath("/api/auth/login/x")));
        for (String entry : List.of("POST /api/auth/**", "TRACE /api/x", "/api/x", "POST /api/other"))
            assertThrows(IllegalArgumentException.class,
                    () -> route("notes", List.of("/api/notes/**"), List.of(entry)), entry);
    }

    // 标识与路径在整组内唯一；限流参数有界。
    @Test
    void identifiersAndPathsAreUnique() {
        var iam = route("iam", List.of("/api/**"), null);
        assertThrows(IllegalArgumentException.class,
                () -> new GatewaySettings(List.of(iam, route("iam", List.of("/api/x"), null)), LIMIT));
        assertThrows(IllegalArgumentException.class,
                () -> new GatewaySettings(List.of(iam, route("other", List.of("/api/**"), null)), LIMIT));
        assertThrows(IllegalArgumentException.class, () -> new GatewaySettings(List.of(), LIMIT));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings(10, 5));
        assertEquals("iam", new GatewaySettings(List.of(iam), LIMIT).route("iam").orElseThrow().id());
    }

    // 字面前缀越长越先匹配，业务服务的具体前缀优先于 IAM 的 /api/**。
    @Test
    void specificityFollowsLiteralPrefix() {
        assertTrue(SettingsRouteLocator.specificity("/api/me/notes") > SettingsRouteLocator.specificity("/api/**"));
        assertTrue(SettingsRouteLocator.specificity("/api/notes/**") > SettingsRouteLocator.specificity("/api/**"));
        assertEquals("/api/notes/".length(), SettingsRouteLocator.specificity("/api/notes/{id}"));
    }

    // 接口限流规则：方法与维度受限、取值有界，接口必须落在本路由的路径内；按顺序取第一条命中的。
    @Test
    void routeLimitsStayInsideRoute() {
        var upload = new RouteLimit("POST /api/notes/*/files", "account", 30, 10);
        var route = new RouteSettings("notes", "svc", List.of("/api/notes/**"), null,
                List.of(upload, new RouteLimit("POST /api/notes/**", "client", 600, 50)));
        assertSame(upload, route.limit("POST", PathContainer.parsePath("/api/notes/7/files")).orElseThrow());
        assertEquals("client", route.limit("POST", PathContainer.parsePath("/api/notes")).orElseThrow().by());
        assertTrue(route.limit("GET", PathContainer.parsePath("/api/notes/7/files")).isEmpty());
        assertEquals(2, upload.retryAfterSeconds());
        assertEquals(1, new RouteLimit("GET /api/x", "client", 600, 1).retryAfterSeconds());
        assertThrows(IllegalArgumentException.class, () -> new RouteSettings("notes", "svc",
                List.of("/api/notes/**"), null, List.of(new RouteLimit("POST /api/other/{id}", "client", 1, 1))));
        for (String endpoint : List.of("TRACE /api/notes", "/api/notes", "POST /internal/x", "POST /api/{"))
            assertThrows(IllegalArgumentException.class, () -> new RouteLimit(endpoint, "client", 1, 1), endpoint);
        assertThrows(IllegalArgumentException.class, () -> new RouteLimit("GET /api/x", "user", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RouteLimit("GET /api/x", "client", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RouteLimit("GET /api/x", "client", 1, 0));
    }

    // 限流按网关实际转发的路由取规则：字面前缀最长的路由，与路由定位器一致。
    @Test
    void routeForPicksMostSpecific() {
        var iam = route("iam", List.of("/api/**"), null);
        var notes = route("notes", List.of("/api/notes/**", "/api/me/notes"), null);
        var settings = new GatewaySettings(List.of(iam, notes), LIMIT);
        assertEquals("notes", settings.routeFor(PathContainer.parsePath("/api/notes/7/files")).orElseThrow().id());
        assertEquals("notes", settings.routeFor(PathContainer.parsePath("/api/me/notes")).orElseThrow().id());
        assertEquals("iam", settings.routeFor(PathContainer.parsePath("/api/accounts")).orElseThrow().id());
        assertTrue(settings.routeFor(PathContainer.parsePath("/healthz")).isEmpty());
    }
}
