package org.domainforge.access.internal;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.SameTokenInvalidException;
import org.domainforge.web.api.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Same-Token 校验失败时 {@code SaServletFilter} 的错误处理：缺失或不符为 401，会话存储不可用为 503；
 * 响应使用 Problem Details，不回显请求中的值（原生异常消息会拼接收到的令牌）。
 */
public final class SameTokenRejections {
    private static final Logger LOG = LoggerFactory.getLogger(SameTokenRejections.class);
    private static final String UNAUTHENTICATED = Problems.json(401, "UNAUTHENTICATED", "需要身份认证");
    private static final String UNAVAILABLE = Problems.json(503, "SERVICE_UNAVAILABLE", "服务暂不可用");

    private SameTokenRejections() {
    }

    /**
     * 设置状态与内容类型，返回写入响应体的文本。
     *
     * @param failure setAuth 中抛出的异常
     * @return 响应体
     */
    public static Object respond(Throwable failure) {
        var response = SaHolder.getResponse();
        response.setHeader("Content-Type", "application/problem+json;charset=UTF-8");
        if (failure instanceof SameTokenInvalidException) {
            LOG.warn("拒绝缺少有效 Same-Token 的请求：{}", SaHolder.getRequest().getRequestPath());
            response.setStatus(401);
            return UNAUTHENTICATED;
        }
        LOG.error("Same-Token 校验失败：会话存储不可用（{}）", failure.getClass().getName());
        response.setStatus(503);
        return UNAVAILABLE;
    }
}
