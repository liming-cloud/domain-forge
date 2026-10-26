package org.domainforge.iam.interfaces.http.protocol;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * IAM具体失败合同复用平台外壳；本单元批准的状态/版本冲突使用409，未知提交保持503。
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
     * 业务拒绝、身份、容量与依赖故障保持可区分，不以200伪报未知结果。
     */
    private static ResponseEntity<ApiResponse<Object>> classified(IamException failure, Object data) {
        return switch (failure.failure()) {
            case INVALID_REQUEST -> response(400, "INVALID_REQUEST", failure.getMessage(), null);
            case UNAUTHENTICATED -> response(401, "UNAUTHENTICATED", failure.getMessage(), null);
            case FORBIDDEN -> response(403, "FORBIDDEN", failure.getMessage(), null);
            case CONFLICT -> response(409, "IAM_CONFLICT", failure.getMessage(), data);
            case RATE_LIMITED -> ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(failure.retryAfterSeconds()))
                    .body(new ApiResponse<>("LOGIN_RATE_LIMITED", failure.getMessage(), null));
            case UNAVAILABLE -> response(503, "SERVICE_UNAVAILABLE", failure.getMessage(), data);
            case UNKNOWN_RESULT -> response(503, "IAM_RESULT_UNKNOWN", failure.getMessage(), data);
        };
    }

    private static ResponseEntity<ApiResponse<Object>> response(
            int status, String code, String message, Object data) {
        return ResponseEntity.status(status).body(new ApiResponse<>(code, message, data));
    }

    /**
     * 外部只返回固定说明；未知失败保留脱敏诊断类别和操作ID，不打印SQL/密码参数。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handle(Exception failure, HttpServletRequest request) {
        Object operation = request.getAttribute("iam.operationId");
        Object data = operation instanceof UUID ? Map.of("operationId", operation) : null;
        if (failure instanceof IamException iam) return classified(iam, data);
        if (invalidRequest(failure)) {
            return response(400, "INVALID_REQUEST", "请求格式或字段无效", null);
        }
        LOG.error(
                "iam_request_failed operationId={} failureType={}",
                operation,
                failure.getClass().getSimpleName());
        if (failure instanceof DataAccessException || failure instanceof TransactionException)
            return response(503, "SERVICE_UNAVAILABLE", "必要服务暂不可用", data);
        return response(500, "INTERNAL_ERROR", "服务内部错误", data);
    }
}
