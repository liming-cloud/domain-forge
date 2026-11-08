package org.domainforge.web.internal;

import org.domainforge.web.api.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/**
 * multipart 解析在选定处理器之前发生（上传过大、格式错误），不属于任何 Controller，所以不能依赖
 * {@code @PlatformJsonApi} 范围的处理；这里只接管 multipart 异常，仍交给唯一分派点。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MultipartExceptionAdvice {
    /**
     * 上传过大为 413，其他 multipart 失败为 400。
     *
     * @param failure multipart 解析异常
     * @return Problem Details 结果
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> handle(MultipartException failure) {
        return Problems.dispatch(failure);
    }
}
