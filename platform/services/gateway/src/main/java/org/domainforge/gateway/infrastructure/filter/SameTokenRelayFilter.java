package org.domainforge.gateway.infrastructure.filter;

import cn.dev33.satoken.same.SaSameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

/**
 * 为转发给后端的请求写入当前 Same-Token（客户端自带的同名头已在 RequestIdentityFilter 中删除）。
 *
 * <p>每次读取 Redis 中的当前值，不缓存；读取失败返回 503，不省略请求头继续转发。
 */
@Component
public class SameTokenRelayFilter implements GlobalFilter, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(SameTokenRelayFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.fromCallable(SaSameUtil::getToken)
                .subscribeOn(Schedulers.boundedElastic())
                .map(Optional::of)
                .onErrorResume(failure -> {
                    LOG.error("gateway_same_token_unavailable reason={}", failure.getClass().getName());
                    return Mono.just(Optional.empty());
                })
                .flatMap(token -> token.isEmpty() ? GatewayResponses.unavailable(exchange)
                        : chain.filter(exchange.mutate()
                                .request(builder -> builder.header(RequestIdentityFilter.SAME_TOKEN, token.get()))
                                .build()));
    }

    /**
     * 登录校验通过之后、转发之前。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
