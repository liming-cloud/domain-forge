package org.domainforge.gateway.infrastructure.filter;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * 丢弃后端响应中的 X-Trace-Id：响应头只保留网关自己写入的值，避免出现两个同名头。
 *
 * <p>后端与网关在同一链路中，两者值相同；网关写入时机晚于后端响应头复制但并不总能覆盖，因此在复制前删除。
 */
@Component
public class BackendTraceIdHeadersFilter implements HttpHeadersFilter {
    private static final String TRACE_ID = "X-Trace-Id";

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        if (!input.containsHeader(TRACE_ID)) return input;
        var headers = new HttpHeaders();
        headers.addAll(input);
        headers.remove(TRACE_ID);
        return headers;
    }

    /**
     * 只处理后端响应头。
     */
    @Override
    public boolean supports(Type type) {
        return type == Type.RESPONSE;
    }
}
