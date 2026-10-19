package org.domainforge.web.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.util.logging.Logger;
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
  private static final Logger LOGGER = Logger.getLogger(PlatformExceptionAdvice.class.getName());
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
    ApiResponse<Void> rejection;
    try {
      rejection = mappings.resolve(failure);
    } catch (RuntimeException invalidMapping) {
      LOGGER.severe(() -> diagnostic("rejection-mapping", failure, invalidMapping));
      return HttpFailureResponses.internalError();
    }
    if (rejection != null) {
      return ResponseEntity.ok(rejection);
    }
    LOGGER.severe(() -> diagnostic("unhandled", failure, failure));
    return HttpFailureResponses.internalError();
  }

  /**
   * 仅保留故障类别、异常类型和代码位置，不读取 message、suppressed 或序列化原始异常。 最多展开四层原因、每层八个栈位置；截断显式标记，避免循环原因或长栈放大日志。
   *
   * @param kind 固定的故障分类
   * @param original 入口原始异常，只读取其类型
   * @param cause 实际处理失败，扩展出错时为扩展异常
   * @return 不超过 4096 字符的内部诊断，不作为 HTTP 响应
   */
  private static String diagnostic(String kind, Exception original, Throwable cause) {
    StringBuilder text =
        new StringBuilder("platform_http_failure kind=")
            .append(kind)
            .append(" original=")
            .append(original.getClass().getName());
    for (int depth = 0; cause != null && depth < 4; depth++) {
      text.append(" cause=").append(cause.getClass().getName());
      StackTraceElement[] stack = cause.getStackTrace();
      for (int index = 0; index < Math.min(stack.length, 8); index++) {
        StackTraceElement frame = stack[index];
        text.append(" at=")
            .append(frame.getClassName())
            .append('.')
            .append(frame.getMethodName())
            .append(':')
            .append(frame.getLineNumber());
      }
      if (stack.length > 8) {
        text.append(" stack-truncated");
      }
      cause = cause.getCause();
    }
    if (cause != null) {
      text.append(" cause-truncated");
    }
    return text.length() <= 4096 ? text.toString() : text.substring(0, 4086) + " truncated";
  }
}
