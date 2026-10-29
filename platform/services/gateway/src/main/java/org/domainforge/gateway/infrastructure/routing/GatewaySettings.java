package org.domainforge.gateway.infrastructure.routing;

import org.springframework.http.server.PathContainer;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * 网关的动态配置组 {@code gateway.routing}：路由与限流。整组绑定，任何一项非法时整组不应用，保持当前路由。
 *
 * @param routes    路由，标识唯一，同一路径只能出现在一条路由中
 * @param rateLimit 限流参数
 */
public record GatewaySettings(List<RouteSettings> routes, RateLimitSettings rateLimit) {
    /**
     * 跨路由校验：标识与路径都不能重复。
     */
    public GatewaySettings {
        if (routes == null || routes.isEmpty()) throw new IllegalArgumentException("至少需要一条路由");
        if (rateLimit == null) throw new IllegalArgumentException("缺少 rate-limit");
        routes = List.copyOf(routes);
        var ids = new HashSet<String>();
        var paths = new HashSet<String>();
        for (RouteSettings route : routes) {
            if (!ids.add(route.id())) throw new IllegalArgumentException("路由标识重复: " + route.id());
            for (String path : route.paths())
                if (!paths.add(path)) throw new IllegalArgumentException("路径出现在多条路由中: " + path);
        }
    }

    /**
     * 请求路径会被转发到的路由：与路由定位器一致，取字面前缀最长的匹配。
     */
    public Optional<RouteSettings> routeFor(PathContainer path) {
        return routes.stream().filter(route -> route.specificity(path) >= 0)
                .max(Comparator.comparingInt(route -> route.specificity(path)));
    }

    /**
     * 按标识查找当前路由。
     */
    public Optional<RouteSettings> route(String id) {
        return routes.stream().filter(route -> route.id().equals(id)).findFirst();
    }
}
