package org.domainforge.web.internal;

import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.web.api.PlatformJsonApi;
import org.domainforge.web.api.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 只处理显式接入 Controller 的异常，不接管未选路由或正常返回值；分派规则全部在 {@link Problems}。
 */
@RestControllerAdvice(annotations = PlatformJsonApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PlatformExceptionAdvice {
    /**
     * 已提交响应不再改写，其余失败交给唯一分派点。
     *
     * @param failure  原始异常
     * @param response 当前 Servlet 响应
     * @return Problem Details 结果
     * @throws Exception 响应已提交，交还宿主处理而不声称转换成功
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle(Exception failure, HttpServletResponse response) throws Exception {
        if (response.isCommitted()) {
            throw failure;
        }
        return Problems.dispatch(failure);
    }
}
