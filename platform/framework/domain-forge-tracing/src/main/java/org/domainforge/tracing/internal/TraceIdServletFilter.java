package org.domainforge.tracing.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在响应头 {@code X-Trace-Id} 返回当前链路的 traceId，供用户报障与日志检索；未采样的链路同样有 traceId。
 *
 * <p>排在 Spring 的服务端观测过滤器之后，此时当前 span 已建立。
 */
public final class TraceIdServletFilter extends OncePerRequestFilter implements Ordered {
    private final Tracer tracer;

    /**
     * 读取 Micrometer Tracing 的当前 span。
     */
    public TraceIdServletFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) response.setHeader(TraceHeaders.TRACE_ID, span.context().traceId());
        chain.doFilter(request, response);
    }

    /**
     * 服务端观测过滤器的顺序为最高优先级加 1。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
