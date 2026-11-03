package org.domainforge.tracing.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 每个请求结束时写一条访问日志 {@code http_access}，日志上下文带当前链路的 traceId，使一次调用在网关与各服务中的日志
 * 可以按 traceId 关联。只记方法、路径（不含查询参数）、状态与耗时，不记请求头和请求体。
 *
 * <p>排在服务端观测过滤器之后，写日志时当前 span 仍在作用域内。管理端口是独立容器，健康检查不经过本过滤器。
 */
public final class AccessLogServletFilter extends OncePerRequestFilter implements Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(AccessLogServletFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            // 异常传出时容器随后按 500 处理，此刻响应状态尚未改写。
            int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            LOG.info("http_access method={} path={} status={} millis={}", request.getMethod(),
                    request.getRequestURI(), status, (System.nanoTime() - start) / 1_000_000);
        }
    }

    /**
     * 服务端观测过滤器为最高优先级加 1，traceId 响应头为加 2。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}
