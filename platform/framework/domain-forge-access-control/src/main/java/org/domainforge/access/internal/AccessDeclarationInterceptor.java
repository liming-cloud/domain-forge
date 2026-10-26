package org.domainforge.access.internal;

import cn.dev33.satoken.stp.StpLogic;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在原生注解执行前确认声明和完整主体；未声明的入口默认拒绝，受保护入口共享本请求同令牌快照。
 */
public final class AccessDeclarationInterceptor implements HandlerInterceptor {
    private final AccessDeclarations declarations;
    private final CurrentAccess current;
    private final StpLogic logic;

    /**
     * 与原生拦截器保持固定先后顺序，不依赖原生执行后的回调补漏。
     */
    public AccessDeclarationInterceptor(AccessDeclarations declarations, CurrentAccess current, StpLogic logic) {
        this.declarations = declarations;
        this.current = current;
        this.logic = logic;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || AccessDeclarations.framework(method)) return true;
        var policy = declarations.policy(method);
        if (policy == AccessDeclarations.Policy.ANONYMOUS) return true;
        if (policy == AccessDeclarations.Policy.UNDECLARED) throw new AccessException(AccessFailure.FORBIDDEN);
        var subject = current.current();
        try {
            if (!String.valueOf(subject.accountId()).equals(String.valueOf(logic.getLoginId())))
                throw new AccessException(AccessFailure.UNAUTHENTICATED);
        } catch (RuntimeException failure) {
            throw NativeFailures.translate(failure);
        }
        return true;
    }
}
