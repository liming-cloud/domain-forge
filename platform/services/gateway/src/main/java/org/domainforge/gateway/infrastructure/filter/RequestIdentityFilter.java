package org.domainforge.gateway.infrastructure.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 确定请求标识与客户端地址，整理转发给后端的请求头，并在请求结束时写访问日志。
 *
 * <p>客户端地址：只有来自可信代理（Nginx 固定地址）的连接才采用其 X-Forwarded-For，否则用连接地址；
 * 转发时由 {@link ClientAddressHeadersFilter} 写入。这里删除 Forwarded、X-Real-IP 与客户端自带的 Same-Token。
 */
@Component
public class RequestIdentityFilter implements WebFilter, Ordered {
    /**
     * 交换属性：解析后的客户端地址，限流按它计数。
     */
    public static final String CLIENT_ADDRESS = "domainforge.client-address";

    static final String REQUEST_ID = "X-Request-Id";
    static final String SAME_TOKEN = "SA-SAME-TOKEN";
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdentityFilter.class);
    private static final Pattern ACCEPTED_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f.:]{2,45}");

    private final Set<String> trustedProxies;

    /**
     * 可信代理地址来自部署环境（如 Nginx 固定地址），为空时不信任任何 X-Forwarded-For。
     */
    public RequestIdentityFilter(@Value("${domainforge.gateway.trusted-proxies:}") List<String> trustedProxies) {
        this.trustedProxies = Set.copyOf(trustedProxies.stream().filter(value -> !value.isBlank()).toList());
    }

    /**
     * 合法的传入标识原样沿用，否则生成新值。
     */
    static String requestId(String supplied) {
        if (supplied != null && ACCEPTED_ID.matcher(supplied).matches()) return supplied;
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 可信代理给出的最后一个转发地址；格式不像 IP 地址时退回连接地址。
     */
    String clientAddress(String connection, String forwardedFor) {
        if (!trustedProxies.contains(connection) || forwardedFor == null || forwardedFor.isBlank()) return connection;
        String[] hops = forwardedFor.split(",");
        String last = hops[hops.length - 1].trim();
        return ADDRESS.matcher(last).matches() ? last : connection;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        String id = requestId(request.getHeaders().getFirst(REQUEST_ID));
        InetSocketAddress remote = request.getRemoteAddress();
        String connection = remote == null || remote.getAddress() == null ? "unknown"
                : remote.getAddress().getHostAddress();
        String client = clientAddress(connection, request.getHeaders().getFirst("X-Forwarded-For"));
        exchange.getAttributes().put(CLIENT_ADDRESS, client);
        // 提交前覆盖：后端（web-support）也会返回同名头，框架合并后不能出现两个值。
        var response = exchange.getResponse();
        response.beforeCommit(() -> {
            response.getHeaders().set(REQUEST_ID, id);
            return Mono.empty();
        });
        var forwarded = exchange.mutate().request(builder -> builder.headers(headers -> rewrite(headers, id)))
                .build();
        long started = System.nanoTime();
        return chain.filter(forwarded).doFinally(signal -> access(forwarded, id, started));
    }

    private static void rewrite(HttpHeaders headers, String id) {
        headers.remove("Forwarded");
        headers.remove("X-Real-IP");
        headers.remove(SAME_TOKEN);
        headers.set(REQUEST_ID, id);
    }

    private static void access(ServerWebExchange exchange, String id, long started) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        var status = exchange.getResponse().getStatusCode();
        LOG.info("gateway_access request_id={} method={} path={} status={} route={} millis={}",
                id,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getRawPath(),
                status == null ? "-" : status.value(),
                route == null ? "-" : route.getId(),
                (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * 紧随路径检查之后，先于路由与其他过滤器。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
