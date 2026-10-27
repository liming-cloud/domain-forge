package org.domainforge.gateway.infrastructure.routing;

import org.domainforge.config.api.DynamicSettings;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 由当前配置快照生成路由：每个路径一条，按字面前缀长度排序，越具体越先匹配（与原 Nginx 的最长前缀一致）。
 *
 * <p>Spring Cloud Gateway 缓存路由，配置刷新完成后（RefreshScopeRefreshedEvent）重新读取本定位器。
 */
@Component
public class SettingsRouteLocator implements RouteLocator {
    /**
     * 路由元数据中的配置路由标识，过滤器据此查找匿名清单。
     */
    public static final String ROUTE_ID = "domainforge.route-id";

    private final DynamicSettings<GatewaySettings> settings;

    /**
     * 每次生成路由都读取当前快照，不保存旧引用。
     */
    public SettingsRouteLocator(DynamicSettings<GatewaySettings> settings) {
        this.settings = settings;
    }

    /**
     * 字面前缀：第一个通配或变量之前的部分。
     */
    static int specificity(String path) {
        int end = path.length();
        for (char marker : new char[]{'*', '{'}) {
            int index = path.indexOf(marker);
            if (index >= 0) end = Math.min(end, index);
        }
        return end;
    }

    @Override
    public Flux<Route> getRoutes() {
        List<Route> routes = new ArrayList<>();
        for (RouteSettings route : settings.current().routes()) {
            for (int index = 0; index < route.paths().size(); index++)
                routes.add(route(route, index));
        }
        return Flux.fromIterable(routes);
    }

    private static Route route(RouteSettings route, int index) {
        String path = route.paths().get(index);
        PathPattern pattern = RouteSettings.pattern(path);
        return Route.async()
                .id(route.id() + "#" + index)
                .uri(URI.create("lb://" + route.service()))
                .order(-specificity(path))
                .metadata(ROUTE_ID, route.id())
                .predicate(exchange -> pattern.matches(exchange.getRequest().getPath().pathWithinApplication()))
                .build();
    }
}
