package org.domainforge.gateway.infrastructure.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 转发失败的统一响应：无可用实例、连接失败为 503，下游响应超时为 504，其他未知故障为 500。
 *
 * <p>404、405 等框架判定保持默认处理；后端已经返回的响应原样透传，不经过这里。日志只记录异常类型。
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayErrorHandler.class);

    /**
     * 沿原因链查找连接类故障。
     */
    static boolean connectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) return true;
            if (cause.getClass().getName().equals("io.netty.channel.ConnectTimeoutException")) return true;
        }
        return false;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (exchange.getResponse().isCommitted()) return Mono.error(failure);
        if (failure instanceof ResponseStatusException status) {
            int code = status.getStatusCode().value();
            if (code == 503) return GatewayResponses.unavailable(exchange);
            if (code == 504) return timeout(exchange);
            if (code < 500) return Mono.error(failure);
        }
        if (connectionFailure(failure)) return GatewayResponses.unavailable(exchange);
        if (failure instanceof TimeoutException) return timeout(exchange);
        LOG.error("gateway_internal_error type={}", failure.getClass().getName());
        return GatewayResponses.failure(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务处理失败");
    }

    private static Mono<Void> timeout(ServerWebExchange exchange) {
        return GatewayResponses.failure(exchange, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT", "下游服务响应超时");
    }
}
