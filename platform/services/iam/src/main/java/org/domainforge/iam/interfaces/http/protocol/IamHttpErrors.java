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
 * IAM 的失败合同：持久化与事务故障在此归类，限流补 {@code Retry-After}；业务拒绝与依赖不可用交给 web-support 的唯一分派点。
 *
 * <p>唯一约束冲突 409（VERSION_CONFLICT），依赖不可用 503，提交结果未知 503（IAM_RESULT_UNKNOWN）。
 */
@RestControllerAdvice(basePackages = "org.domainforge.iam.interfaces.http")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IamHttpErrors {
    private static final Logger LOG = LoggerFactory.getLogger(IamHttpErrors.class);

    /**
     * 外部只返回固定说明；内部日志只记录失败类别，不打印 SQL、密码或令牌。
     *
     * <p>提交阶段失败时数据库可能已经提交，也可能没有：返回 IAM_RESULT_UNKNOWN，客户端应先读取业务事实再决定是否重试。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle(Exception failure) {
        if (failure instanceof IamException iam && iam.retryAfterSeconds() > 0) return limited(iam);
        if (failure instanceof TransactionSystemException) {
            LOG.error("iam_request_failed failureType={}", failure.getClass().getSimpleName());
            return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "IAM_RESULT_UNKNOWN", "处理结果未知，请查询后再操作");
        }
        if (failure instanceof DataIntegrityViolationException) {
            LOG.error("iam_request_failed failureType={}", failure.getClass().getSimpleName());
            return Problems.of(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已被并发修改，请重新读取后再操作");
        }
        if (failure instanceof DataAccessException || failure instanceof TransactionException) {
            LOG.error("iam_request_failed failureType={}", failure.getClass().getSimpleName());
            return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "必要服务暂不可用");
        }
        return Problems.dispatch(failure);
    }

    private static ResponseEntity<ProblemDetail> limited(IamException failure) {
        var limited = Problems.dispatch(failure);
        return ResponseEntity.status(limited.getStatusCode())
                .header("Retry-After", Long.toString(failure.retryAfterSeconds()))
                .body(limited.getBody());
    }
}
