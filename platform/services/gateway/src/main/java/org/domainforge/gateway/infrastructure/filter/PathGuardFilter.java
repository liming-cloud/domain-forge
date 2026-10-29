package org.domainforge.gateway.infrastructure.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * 路由之前拒绝可能被后端规范化成其他路径的请求（400）。
 *
 * <p>网关按原始路径匹配路由、原样转发，后端 Tomcat 会解码并规范化路径：{@code /api/%2e%2e/internal/...}
 * 能命中 {@code /api/**}，到后端却落到 {@code /internal/}。因此编码的点号与斜杠、反斜杠、路径参数、
 * 二次编码、{@code .}／{@code ..} 段和空段一律拒绝，不尝试“安全地规范化”。
 */
@Component
public class PathGuardFilter implements WebFilter, Ordered {
    private static final List<String> FORBIDDEN = List.of("%2e", "%2f", "%5c", "%25", "%00", ";", "\\", "//");

    /**
     * 判断原始路径是否可以安全转发。
     */
    static boolean safe(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN) if (lower.contains(token)) return false;
        for (String segment : lower.split("/", -1)) if (segment.equals(".") || segment.equals("..")) return false;
        return true;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (safe(exchange.getRequest().getURI().getRawPath())) return chain.filter(exchange);
        return GatewayResponses.failure(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不合法");
    }

    /**
     * 先于确定客户端地址与路由执行。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
