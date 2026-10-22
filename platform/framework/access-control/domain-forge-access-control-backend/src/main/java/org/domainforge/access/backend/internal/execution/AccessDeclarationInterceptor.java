package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpLogic;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 原生注解执行前先确认声明和完整主体，所有受保护入口共享本请求同令牌快照。 */
public final class AccessDeclarationInterceptor implements HandlerInterceptor {
  private final AccessDeclarations declarations;
  private final CurrentAccess current;
  private final StpLogic logic;

  /** 与原生拦截器保持固定先后顺序，不使用原生注解后的回调补漏。 */
  public AccessDeclarationInterceptor(
      AccessDeclarations declarations, CurrentAccess current, StpLogic logic) {
    this.declarations = declarations;
    this.current = current;
    this.logic = logic;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method) || framework(method)) return true;
    var policy = declarations.policy(method);
    if (policy == AccessDeclarations.Policy.ANONYMOUS) return true;
    if (policy == AccessDeclarations.Policy.UNDECLARED)
      throw new AccessException(AccessFailure.FORBIDDEN);
    var subject = current.current();
    try {
      if (!subject.accountId().toString().equals(String.valueOf(logic.getLoginId())))
        throw new AccessException(AccessFailure.UNAUTHENTICATED);
    } catch (NotLoginException failure) {
      var rejection = new NotLoginException("需要有效登录", failure.getLoginType(), failure.getType());
      rejection.setCode(failure.getCode());
      throw rejection;
    } catch (AccessException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AccessException(AccessFailure.UNAVAILABLE);
    }
    return true;
  }

  /** 框架自有处理器不被误认成业务接口；应用控制器没有默认匿名豁免。 */
  public static boolean framework(HandlerMethod method) {
    return method.getBeanType().getPackageName().startsWith("org.springframework.");
  }
}
