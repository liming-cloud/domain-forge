package org.domainforge.web.internal;

import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.web.api.ApiResponse;
import org.domainforge.web.api.HttpFailureResponses;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只处理显式接入 Controller 的异常，不接管未选路由或正常返回值。 */
@RestControllerAdvice(annotations = PlatformJsonApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PlatformExceptionAdvice {
  private final RejectionMappings mappings;

  /**
   * 注入启动时已验证的映射表。
   *
   * @param mappings 确切类型映射表
   */
  public PlatformExceptionAdvice(RejectionMappings mappings) {
    this.mappings = mappings;
  }

  /**
   * 已提交响应不再改写；协议错误优先，扩展失败与未知异常统一安全失败。
   *
   * @param failure 原始异常
   * @param response 当前 Servlet 响应
   * @return 明确分类的 HTTP 结果
   * @throws Exception 响应已提交，交还宿主处理而不声称转换成功
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handle(Exception failure, HttpServletResponse response)
      throws Exception {
    if (response.isCommitted()) {
      throw failure;
    }
    if (failure instanceof HttpMessageNotReadableException
        || failure instanceof TypeMismatchException
        || failure instanceof ServletRequestBindingException) {
      return HttpFailureResponses.invalidRequest();
    }
    try {
      ApiResponse<Void> rejection = mappings.resolve(failure);
      return rejection == null
          ? HttpFailureResponses.internalError()
          : ResponseEntity.ok(rejection);
    } catch (RuntimeException invalidMapping) {
      return HttpFailureResponses.internalError();
    }
  }
}
