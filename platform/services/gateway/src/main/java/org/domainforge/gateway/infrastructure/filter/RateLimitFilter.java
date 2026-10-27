package org.domainforge.gateway.infrastructure.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.cache.api.RedisScript;
import org.domainforge.cache.api.RedisScriptOperations;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.gateway.infrastructure.routing.GatewaySettings;
import org.domainforge.gateway.infrastructure.routing.RateLimitSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 按客户端地址的 Redis 令牌桶，超限返回 429。
 *
 * <p>这是过载保护，不是安全边界：Redis 不可用时放行，记录错误日志与计数器
 * {@code domainforge.gateway.rate-limit.unavailable} 供告警（设计见 docs/architecture/06-API网关.md#限流）。
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final RedisScript<Long> BUCKET =
            new RedisScript<>("scripts/gateway-rate-limit.lua", Long.class, "gateway:rate:");

    private final RedisScriptOperations scripts;
    private final DynamicSettings<GatewaySettings> settings;
    private final Counter unavailable;

    /**
     * 限流参数每次请求读取当前快照，动态调整立即生效。
     */
    public RateLimitFilter(
            RedisScriptOperations scripts, DynamicSettings<GatewaySettings> settings, MeterRegistry metrics) {
        this.scripts = scripts;
        this.settings = settings;
        this.unavailable = Counter.builder("domainforge.gateway.rate-limit.unavailable").register(metrics);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String client = exchange.getAttributeOrDefault(RequestIdentityFilter.CLIENT_ADDRESS, "unknown");
        RateLimitSettings limit = settings.current().rateLimit();
        return Mono.fromCallable(() -> allowed(client, limit))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(allowed -> allowed ? chain.filter(exchange) : limited(exchange, limit));
    }

    private boolean allowed(String client, RateLimitSettings limit) {
        try {
            Long result = scripts.execute(BUCKET, List.of("gateway:rate:" + client),
                    List.of(Integer.toString(limit.replenishPerSecond()), Integer.toString(limit.burst())));
            return result != null && result == 1L;
        } catch (RuntimeException failure) {
            unavailable.increment();
            LOG.error("gateway_rate_limit_unavailable reason={}", failure.getClass().getName());
            return true;
        }
    }

    private static Mono<Void> limited(ServerWebExchange exchange, RateLimitSettings limit) {
        long retryAfter = Math.max(1, (long) Math.ceil(1.0 / limit.replenishPerSecond()));
        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfter));
        return GatewayResponses.failure(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后重试");
    }

    /**
     * 路由之后、登录校验之前：匿名的登录接口同样受限。
     */
    @Override
    public int getOrder() {
        return -300;
    }
}
