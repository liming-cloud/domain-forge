package org.domainforge.iam.interfaces.http.protocol;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.shared.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * IAM 失败合同复用平台外壳：状态或版本冲突 409，限流 429，依赖不可用 503，提交结果未知 503（IAM_RESULT_UNKNOWN）。
 */
@RestControllerAdvice(basePackages = "org.domainforge.iam.interfaces.http")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IamHttpErrors {
    private static final Logger LOG = LoggerFactory.getLogger(IamHttpErrors.class);

    private static boolean invalidRequest(Exception failure) {
        return failure instanceof HttpMessageNotReadableException
                || failure instanceof TypeMismatchException
                || failure instanceof ServletRequestBindingException
                || failure instanceof IllegalArgumentException;
    }

    /**
     * 业务拒绝、身份、容量与依赖故障保持可区分，不以 200 伪报结果。
     */
    private static ResponseEntity<ApiResponse<Object>> classified(IamException failure) {
        return switch (failure.failure()) {
            case INVALID_REQUEST -> response(400, "INVALID_REQUEST", failure.getMessage());
            case UNAUTHENTICATED -> response(401, "UNAUTHENTICATED", failure.getMessage());
            case FORBIDDEN -> response(403, "FORBIDDEN", failure.getMessage());
            case CONFLICT -> response(409, "IAM_CONFLICT", failure.getMessage());
            case RATE_LIMITED -> ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(failure.retryAfterSeconds()))
                    .body(new ApiResponse<>("LOGIN_RATE_LIMITED", failure.getMessage(), null));
            case UNAVAILABLE -> response(503, "SERVICE_UNAVAILABLE", failure.getMessage());
        };
    }

    private static ResponseEntity<ApiResponse<Object>> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiResponse<>(code, message, null));
    }

    /**
     * 外部只返回固定说明；内部日志只记录失败类别，不打印 SQL、密码或令牌。
     *
     * <p>提交阶段失败时数据库可能已经提交，也可能没有：返回 IAM_RESULT_UNKNOWN，客户端应先读取业务事实再决定是否重试。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handle(Exception failure) {
        if (failure instanceof IamException iam) return classified(iam);
        if (invalidRequest(failure)) return response(400, "INVALID_REQUEST", "请求格式或字段无效");
        LOG.error("iam_request_failed failureType={}", failure.getClass().getSimpleName());
        if (failure instanceof TransactionSystemException)
            return response(503, "IAM_RESULT_UNKNOWN", "处理结果未知，请查询后再操作");
        if (failure instanceof DataAccessException || failure instanceof TransactionException)
            return response(503, "SERVICE_UNAVAILABLE", "必要服务暂不可用");
        return response(500, "INTERNAL_ERROR", "服务内部错误");
    }
}
