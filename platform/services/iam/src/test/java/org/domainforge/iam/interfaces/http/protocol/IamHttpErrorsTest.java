package org.domainforge.iam.interfaces.http.protocol;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IamHttpErrorsTest {
    private final IamHttpErrors errors = new IamHttpErrors();

    // 工作池暂时饱和不能向客户端谎报账号仍受限十分钟。
    @Test
    void hashCapacityUsesShortRetryAdvice() {
        var response = errors.handle(new IamException(IamFailure.RATE_LIMITED, "密码计算繁忙，请稍后重试"));
        assertEquals(429, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
    }

    // 每个稳定失败分类对应固定状态码与错误码。
    @Test
    void classifiedFailuresKeepDistinctStatus() {
        assertStatus(400, "INVALID_REQUEST", new IamException(IamFailure.INVALID_REQUEST, "x"));
        assertStatus(401, "UNAUTHENTICATED", new IamException(IamFailure.UNAUTHENTICATED, "x"));
        assertStatus(403, "FORBIDDEN", new IamException(IamFailure.FORBIDDEN, "x"));
        assertStatus(409, "IAM_CONFLICT", new IamException(IamFailure.CONFLICT, "x"));
        assertStatus(503, "SERVICE_UNAVAILABLE", new IamException(IamFailure.UNAVAILABLE, "x"));
        assertNull(errors.handle(new IamException(IamFailure.CONFLICT, "x")).getHeaders().getFirst("Retry-After"));
    }

    // 提交阶段失败结果未知；普通依赖故障为不可用；未知异常不泄露消息。
    @Test
    void infrastructureFailuresDoNotLeakDetails() {
        assertStatus(503, "IAM_RESULT_UNKNOWN", new TransactionSystemException("commit failed"));
        assertStatus(503, "SERVICE_UNAVAILABLE", new DataAccessResourceFailureException("db down"));
        assertStatus(400, "INVALID_REQUEST", new IllegalArgumentException("secret detail"));
        var internal = errors.handle(new IllegalStateException("secret detail"));
        assertEquals(500, internal.getStatusCode().value());
        assertEquals("服务内部错误", internal.getBody().message());
    }

    private void assertStatus(int status, String code, Exception failure) {
        var response = errors.handle(failure);
        assertEquals(status, response.getStatusCode().value());
        assertEquals(code, response.getBody().code());
    }
}
