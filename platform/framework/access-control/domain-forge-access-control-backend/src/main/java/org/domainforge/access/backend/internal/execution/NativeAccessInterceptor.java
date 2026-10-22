package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;

/** 原生完整注解执行的故障边界；后续存储读取失败保持503，不改写原生授权判定。 */
public final class NativeAccessInterceptor extends SaInterceptor {
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    try {
      return super.preHandle(request, response, handler);
    } catch (NotLoginException failure) {
      var rejection = new NotLoginException("需要有效登录", failure.getLoginType(), failure.getType());
      rejection.setCode(failure.getCode());
      throw rejection;
    } catch (NotPermissionException | NotRoleException | AccessException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AccessException(AccessFailure.UNAVAILABLE);
    }
  }
}
