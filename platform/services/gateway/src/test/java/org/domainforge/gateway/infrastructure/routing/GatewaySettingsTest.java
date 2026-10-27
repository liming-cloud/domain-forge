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
        return new RouteSettings(id, "svc", paths, anonymous);
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
}
