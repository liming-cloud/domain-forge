package org.domainforge.gateway.infrastructure.filter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关自身产生的失败响应，使用与 Web 组件相同的三字段外壳；代码与提示都是固定文本，不回显请求内容。
 */
public final class GatewayResponses {
    private GatewayResponses() {
    }

    /**
     * 写出无数据的失败外壳。
     *
     * @param exchange 当前交换
     * @param status   HTTP 状态
     * @param code     平台稳定代码
     * @param message  固定安全提示
     * @return 写出完成信号
     */
    public static Mono<Void> failure(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 未认证。
     */
    public static Mono<Void> unauthenticated(ServerWebExchange exchange) {
        return failure(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "需要身份认证");
    }

    /**
     * 依赖不可用。
     */
    public static Mono<Void> unavailable(ServerWebExchange exchange) {
        return failure(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "服务暂不可用");
    }
}
