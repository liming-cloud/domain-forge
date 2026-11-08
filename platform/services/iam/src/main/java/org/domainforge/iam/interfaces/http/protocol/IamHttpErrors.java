package org.domainforge.iam.interfaces.http.protocol;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.web.api.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * IAM 的失败合同：应用层拒绝、持久化与事务故障在此归类，其余失败交给 web-support 的唯一分派点。
 *
 * <p>状态、版本或唯一约束冲突 409，限流 429（带 {@code Retry-After}），依赖不可用 503，提交结果未知 503（IAM_RESULT_UNKNOWN）。
 */
@RestControllerAdvice(basePackages = "org.domainforge.iam.interfaces.http")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IamHttpErrors {
    private static final Logger LOG = LoggerFactory.getLogger(IamHttpErrors.class);

    /**
     * 业务拒绝、身份、容量与依赖故障保持可区分，不以 200 伪报结果。
     */
    private static ResponseEntity<ProblemDetail> classified(IamException failure) {
        return switch (failure.failure()) {
            case INVALID_REQUEST -> Problems.of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", failure.getMessage());
            case UNAUTHENTICATED -> Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", failure.getMessage());
            case FORBIDDEN -> Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", failure.getMessage());
            case CONFLICT -> Problems.of(HttpStatus.CONFLICT, "IAM_CONFLICT", failure.getMessage());
            case RATE_LIMITED -> {
                var limited = Problems.of(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", failure.getMessage());
                yield ResponseEntity.status(limited.getStatusCode())
                        .header("Retry-After", Long.toString(failure.retryAfterSeconds()))
                        .body(limited.getBody());
            }
            case UNAVAILABLE ->
                    Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", failure.getMessage());
        };
    }

    /**
     * 外部只返回固定说明；内部日志只记录失败类别，不打印 SQL、密码或令牌。
     *
     * <p>提交阶段失败时数据库可能已经提交，也可能没有：返回 IAM_RESULT_UNKNOWN，客户端应先读取业务事实再决定是否重试。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle(Exception failure) {
        if (failure instanceof IamException iam) return classified(iam);
        if (failure instanceof TransactionSystemException) {
            LOG.error("iam_request_failed failureType={}", failure.getClass().getSimpleName());
            return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "IAM_RESULT_UNKNOWN", "处理结果未知，请查询后再操作");
        }
        if (failure instanceof DataIntegrityViolationException) {
            LOG.error("iam_request_failed failureType={}", failure.getClass().getSimpleName());
            return Problems.of(HttpStatus.CONFLICT, "IAM_CONFLICT", "数据已被并发修改，请重新读取后再操作");
        }
        if (failure instanceof DataAccessException || failure instanceof TransactionException) {
            LOG.error("iam_request_failed failureType={}", failure.getClass().getSimpleName());
            return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "必要服务暂不可用");
        }
        return Problems.dispatch(failure);
    }
}
