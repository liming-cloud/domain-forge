package org.domainforge.web.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求标识：沿用入口给出的 X-Request-Id，缺失或格式不符时重新生成；写入日志上下文与响应头，只用于关联日志。
 *
 * <p>不作为幂等键或安全依据，因此不校验来源；格式限制只为防止日志注入和超长值。
 */
public final class RequestIdFilter extends OncePerRequestFilter implements Ordered {
    /**
     * Nginx 生成、网关透传的请求头。
     */
    public static final String HEADER = "X-Request-Id";

    /**
     * 日志上下文中的键，默认日志格式按此输出。
     */
    public static final String MDC_KEY = "requestId";

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * 合法的传入值原样沿用，否则生成不含分隔符的随机值。
     *
     * @param supplied 请求头中的值，可能为空
     * @return 本次请求使用的标识
     */
    static String requestId(String supplied) {
        if (supplied != null && ACCEPTED.matcher(supplied).matches()) return supplied;
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 请求结束后清除日志上下文，线程复用时不串号。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = requestId(request.getHeader(HEADER));
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 最先执行，之后的过滤器（如 Same-Token 拒绝）产生的日志也带标识。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
