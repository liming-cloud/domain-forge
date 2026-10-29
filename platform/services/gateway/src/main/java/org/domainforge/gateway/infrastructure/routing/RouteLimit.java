package org.domainforge.gateway.infrastructure.routing;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

import java.util.Set;

/**
 * 路由内某个接口的限流规则：按账号或客户端地址的令牌桶，在全局按地址限流之外再加一层。
 *
 * <p>按账号的规则只对已登录的请求按账号计数；接口在匿名清单中时按客户端地址计数。
 *
 * @param endpoint           “方法 路径模式”，如 {@code POST /api/notes/*}{@code /files}，必须落在本路由的路径内
 * @param by                 {@code account} 或 {@code client}
 * @param replenishPerMinute 每分钟补充的令牌数，1—600000
 * @param burst              桶容量，1—100000
 */
public record RouteLimit(String endpoint, String by, int replenishPerMinute, int burst) {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> DIMENSIONS = Set.of("account", "client");

    /**
     * 校验格式与取值范围；是否落在路由路径内由 {@link RouteSettings} 校验。
     */
    public RouteLimit {
        int space = endpoint == null ? -1 : endpoint.indexOf(' ');
        if (space < 0 || !METHODS.contains(endpoint.substring(0, space)))
            throw new IllegalArgumentException("限流接口格式不合法: " + endpoint);
        RouteSettings.pattern(endpoint.substring(space + 1));
        if (by == null || !DIMENSIONS.contains(by)) throw new IllegalArgumentException("限流维度只能是 account 或 client");
        if (replenishPerMinute < 1 || replenishPerMinute > 600_000)
            throw new IllegalArgumentException("replenish-per-minute 须在 1—600000 之间");
        if (burst < 1 || burst > 100_000) throw new IllegalArgumentException("burst 须在 1—100000 之间");
    }

    /**
     * 方法部分。
     */
    public String method() {
        return endpoint.substring(0, endpoint.indexOf(' '));
    }

    /**
     * 路径模式部分。
     */
    public PathPattern pattern() {
        return RouteSettings.pattern(endpoint.substring(endpoint.indexOf(' ') + 1));
    }

    /**
     * 请求是否命中本规则。
     */
    public boolean matches(String requestMethod, PathContainer path) {
        return method().equals(requestMethod) && pattern().matches(path);
    }

    /**
     * 是否按账号计数。
     */
    public boolean byAccount() {
        return by.equals("account");
    }

    /**
     * 超限时建议的重试间隔（秒）：补充一个令牌所需时间，至少 1 秒。
     */
    public long retryAfterSeconds() {
        return Math.max(1, (long) Math.ceil(60.0 / replenishPerMinute));
    }
}
