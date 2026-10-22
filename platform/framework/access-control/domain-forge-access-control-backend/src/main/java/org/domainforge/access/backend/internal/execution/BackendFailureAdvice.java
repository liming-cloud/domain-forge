package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.domainforge.access.api.AccessException;
import org.domainforge.web.api.ApiResponse;
import org.domainforge.web.api.HttpFailureResponses;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只处理本组件及原生访问失败，宿主业务异常仍由宿主自己的合同处理。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class BackendFailureAdvice {
  /** 原生身份失败按401拒绝，不建立匿名身份。 */
  @ExceptionHandler(NotLoginException.class)
  public ResponseEntity<ApiResponse<Void>> unauthenticated() {
    return HttpFailureResponses.unauthenticated();
  }

  /** 原生权限不足按403拒绝。 */
  @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
  public ResponseEntity<ApiResponse<Void>> forbidden() {
    return HttpFailureResponses.forbidden();
  }

  /** 远端不可验证属于503，不能误写成用户自身权限不足或允许。 */
  @ExceptionHandler(AccessException.class)
  public ResponseEntity<ApiResponse<Void>> access(AccessException failure) {
    return switch (failure.failure()) {
      case UNAUTHENTICATED -> HttpFailureResponses.unauthenticated();
      case FORBIDDEN -> HttpFailureResponses.forbidden();
      case UNAVAILABLE -> HttpFailureResponses.unavailable();
    };
  }
}
