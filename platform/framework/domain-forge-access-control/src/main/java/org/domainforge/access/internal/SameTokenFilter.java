package org.domainforge.access.internal;

import cn.dev33.satoken.same.SaSameUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.access.api.SameTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 后端入口先校验 Same-Token：缺失或不符返回 401，会话 Redis 不可用返回 503，均不进入业务处理。
 *
 * <p>只读取当前值与轮换前的上一个值做比较，不调用 Sa-Token 的 isValid（值缺失时它会生成新值，校验方不应写入）。
 * 管理端点在独立端口，不经过本过滤器。
 */
public final class SameTokenFilter extends OncePerRequestFilter implements Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(SameTokenFilter.class);
    private static final String UNAUTHENTICATED =
            "{\"code\":\"UNAUTHENTICATED\",\"message\":\"需要身份认证\",\"data\":null}";
    private static final String UNAVAILABLE =
            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"服务暂不可用\",\"data\":null}";

    /**
     * 常量时间比较，避免按响应时间逐字符猜测。
     */
    private static boolean matches(String presented, String expected) {
        return expected != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 当前值或轮换前的值之一相符即通过。
     */
    private static boolean valid(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        return matches(presented, SaSameUtil.getTokenNh()) || matches(presented, SaSameUtil.getPastTokenNh());
    }

    private static void reject(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean accepted;
        try {
            accepted = valid(request.getHeader(SameTokens.HEADER));
        } catch (RuntimeException failure) {
            LOG.error("Same-Token 校验失败：会话存储不可用（{}）", failure.getClass().getName());
            reject(response, 503, UNAVAILABLE);
            return;
        }
        if (!accepted) {
            LOG.warn("拒绝缺少有效 Same-Token 的请求：{} {}", request.getMethod(), request.getRequestURI());
            reject(response, 401, UNAUTHENTICATED);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 紧随请求标识之后、业务过滤器之前执行，被拒请求不读取请求体。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
