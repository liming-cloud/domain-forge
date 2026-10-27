package org.domainforge.gateway.infrastructure.filter;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * 转发前最后一步写入 X-Forwarded-For：值为网关确定的客户端地址，后端只信任网关地址给出的该头。
 *
 * <p>Spring Cloud Gateway 在未配置其可信代理时会删除全部 X-Forwarded-* 请求头，因此不能在全局过滤器中设置，
 * 必须作为排在最后的请求头过滤器写入。
 */
@Component
public class ClientAddressHeadersFilter implements HttpHeadersFilter, Ordered {
    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        String client = exchange.getAttribute(RequestIdentityFilter.CLIENT_ADDRESS);
        if (client == null) return input;
        var headers = new HttpHeaders();
        headers.addAll(input);
        headers.set("X-Forwarded-For", client);
        return headers;
    }

    /**
     * 晚于框架自带的转发头处理。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
