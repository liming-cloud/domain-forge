package org.domainforge.access.internal;

import cn.dev33.satoken.interceptor.SaInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 原生注解执行的故障边界；存储读取失败保持 503，不改写原生授权判定。
 */
public final class NativeAccessInterceptor extends SaInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        try {
            return super.preHandle(request, response, handler);
        } catch (RuntimeException failure) {
            throw NativeFailures.translate(failure);
        }
    }
}
