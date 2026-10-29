package org.domainforge.gateway.infrastructure.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.cache.api.RedisScript;
import org.domainforge.cache.api.RedisScriptOperations;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.gateway.infrastructure.routing.GatewaySettings;
import org.domainforge.gateway.infrastructure.routing.RouteLimit;
import org.domainforge.gateway.infrastructure.routing.RouteSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 按路由声明的接口限流：请求会被转发到的路由（字面前缀最长）中第一条命中的规则，按账号或客户端地址计数。
 * 令牌桶在 Redis 中，多个网关实例共享；规则随网关配置动态生效。
 *
 * <p>排在登录校验之后，按账号计数时使用已校验会话的账号；匿名接口没有账号，按客户端地址计数。
 * 与全局限流一致，Redis 不可用时放行并计数 {@code domainforge.gateway.rate-limit.unavailable}。
 */
@Component
public class RouteRateLimitFilter implements WebFilter, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(RouteRateLimitFilter.class);
    private static final String PREFIX = "gateway:route-rate:";
    private static final RedisScript<Long> BUCKET =
            new RedisScript<>("scripts/gateway-rate-limit.lua", Long.class, PREFIX);

    private final RedisScriptOperations scripts;
    private final DynamicSettings<GatewaySettings> settings;
    private final MeterRegistry metrics;
    private final Counter unavailable;

    /**
     * 规则每次请求读取当前快照。
     */
    public RouteRateLimitFilter(
            RedisScriptOperations scripts, DynamicSettings<GatewaySettings> settings, MeterRegistry metrics) {
        this.scripts = scripts;
        this.settings = settings;
        this.metrics = metrics;
        this.unavailable = Counter.builder("domainforge.gateway.rate-limit.unavailable").register(metrics);
    }

    /**
     * 桶的键：路由、规则序号与计数对象；账号与客户端地址分开命名，互不冲突。
     */
    static String key(String route, int rule, String account, String client) {
        return PREFIX + route + ":" + rule + ":" + (account == null ? "client:" + client : "account:" + account);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        PathContainer path = request.getPath().pathWithinApplication();
        String method = request.getMethod().name();
        RouteSettings route = settings.current().routeFor(path).orElse(null);
        RouteLimit limit = route == null ? null : route.limit(method, path).orElse(null);
        if (limit == null) return chain.filter(exchange);
        String account = limit.byAccount() ? exchange.getAttribute(LoginCheck.ACCOUNT) : null;
        String client = exchange.getAttributeOrDefault(RequestIdentityFilter.CLIENT_ADDRESS, "unknown");
        String key = key(route.id(), route.limits().indexOf(limit), account, client);
        return Mono.fromCallable(() -> allowed(key, limit))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(allowed -> allowed ? chain.filter(exchange) : limited(exchange, route.id(), limit));
    }

    private boolean allowed(String key, RouteLimit limit) {
        try {
            Long result = scripts.execute(BUCKET, List.of(key), List.of(
                    Double.toString(limit.replenishPerMinute() / 60.0), Integer.toString(limit.burst())));
            return result != null && result == 1L;
        } catch (RuntimeException failure) {
            unavailable.increment();
            LOG.error("gateway_rate_limit_unavailable reason={}", failure.getClass().getName());
            return true;
        }
    }

    private Mono<Void> limited(ServerWebExchange exchange, String route, RouteLimit limit) {
        metrics.counter("domainforge.gateway.route-limit.rejected", "route", route, "by", limit.by()).increment();
        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(limit.retryAfterSeconds()));
        return GatewayResponses.failure(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后重试");
    }

    /**
     * 登录校验（Sa-Token 全局过滤器，-100）之后，账号已确定。
     */
    @Override
    public int getOrder() {
        return -99;
    }
}
