package org.domainforge.tracing.internal;

import org.domainforge.tracing.api.ReactiveTraces;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 响应式应用（网关）在响应头 {@code X-Trace-Id} 返回当前链路的 traceId。
 *
 * <p>响应式请求中线程本地的当前 span 不可靠，从交换属性中的服务端观测上下文取 span。
 */
public final class TraceIdWebFilter implements WebFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 提交前覆盖：网关转发时后端也会返回同名头，框架合并后不能出现两个值。
        ReactiveTraces.traceId(exchange).ifPresent(id -> {
            var response = exchange.getResponse();
            response.beforeCommit(() -> {
                response.getHeaders().set(TraceHeaders.TRACE_ID, id);
                return Mono.empty();
            });
        });
        return chain.filter(exchange);
    }

    /**
     * 最先执行：被后续过滤器拒绝的请求同样带 traceId。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
