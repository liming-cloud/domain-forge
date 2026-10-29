package org.domainforge.tracing.api;

import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

/**
 * 响应式请求的链路信息：响应式处理中线程本地的当前 span 不可靠，从交换属性中的服务端观测上下文读取。
 */
public final class ReactiveTraces {
    private ReactiveTraces() {
    }

    /**
     * 当前请求的 traceId；没有观测上下文（未启用追踪）时为空。
     *
     * @param exchange 当前交换
     * @return traceId
     */
    public static Optional<String> traceId(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> context.<TracingObservationHandler.TracingContext>get(
                        TracingObservationHandler.TracingContext.class))
                .map(TracingObservationHandler.TracingContext::getSpan)
                .map(span -> span.context().traceId());
    }
}
