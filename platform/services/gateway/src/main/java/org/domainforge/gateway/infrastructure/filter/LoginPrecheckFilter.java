package org.domainforge.gateway.infrastructure.filter;

import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.gateway.infrastructure.routing.GatewaySettings;
import org.domainforge.gateway.infrastructure.routing.SettingsRouteLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 登录前置校验：不在匿名清单中的请求必须带有原生会话存在的 Bearer 令牌，否则 401；会话存储不可用 503。
 *
 * <p>只是提前拒绝，后端仍完整认证（SQL 许可、凭据版本）与授权；令牌原样转发，不改写、不记录。
 */
@Component
public class LoginPrecheckFilter implements GlobalFilter, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(LoginPrecheckFilter.class);
    private static final String BEARER = "Bearer ";

    private final DynamicSettings<GatewaySettings> settings;
    private final StpLogic logic;

    /**
     * 匿名清单每次读取当前快照。
     */
    public LoginPrecheckFilter(DynamicSettings<GatewaySettings> settings, StpLogic logic) {
        this.settings = settings;
        this.logic = logic;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (anonymous(exchange)) return chain.filter(exchange);
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith(BEARER) || header.length() == BEARER.length())
            return GatewayResponses.unauthenticated(exchange);
        String token = header.substring(BEARER.length());
        return Mono.fromCallable(() -> logic.getLoginIdByToken(token) == null ? Session.ABSENT : Session.PRESENT)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(failure -> {
                    LOG.error("gateway_session_unavailable reason={}", failure.getClass().getName());
                    return Mono.just(Session.UNAVAILABLE);
                })
                .flatMap(session -> switch (session) {
                    case PRESENT -> chain.filter(exchange);
                    case ABSENT -> GatewayResponses.unauthenticated(exchange);
                    case UNAVAILABLE -> GatewayResponses.unavailable(exchange);
                });
    }

    private boolean anonymous(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) return false;
        Object id = route.getMetadata().get(SettingsRouteLocator.ROUTE_ID);
        var request = exchange.getRequest();
        var path = request.getPath().pathWithinApplication();
        return settings.current().route(String.valueOf(id))
                .map(found -> found.allowsAnonymous(request.getMethod().name(), path))
                .orElse(false);
    }

    /**
     * 原生会话查询结果。
     */
    private enum Session {
        PRESENT, ABSENT, UNAVAILABLE
    }

    /**
     * 限流之后、写入 Same-Token 之前。
     */
    @Override
    public int getOrder() {
        return -200;
    }
}
