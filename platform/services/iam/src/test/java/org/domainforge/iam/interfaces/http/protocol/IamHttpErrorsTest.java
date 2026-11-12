package org.domainforge.iam.interfaces.http.protocol;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamUnavailableException;
import org.domainforge.iam.domain.IamRuleViolation;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionSystemException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IamHttpErrorsTest {
    private final IamHttpErrors errors = new IamHttpErrors();

    // 工作池暂时饱和不能向客户端谎报账号仍受限十分钟。
    @Test
    void hashCapacityUsesShortRetryAdvice() {
        var response = errors.handle(IamException.rateLimited("PASSWORD_BUSY", "密码计算繁忙，请稍后重试", 1));
        assertEquals(429, response.getStatusCode().value());
        assertEquals("PASSWORD_BUSY", response.getBody().getProperties().get("code"));
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
    }

    // 应用层拒绝经唯一分派点按类别转换，错误码原样保留；不存在的目标为 404，引用的资源不存在为 422。
    @Test
    void applicationRejectionsUseDispatchPoint() {
        assertStatus(400, "INVALID_REQUEST", IamException.invalid("x"));
        assertStatus(401, "LOGIN_FAILED", IamException.unauthenticated("LOGIN_FAILED", "x"));
        assertStatus(403, "FORBIDDEN", IamException.forbidden("x"));
        assertStatus(404, "ACCOUNT_NOT_FOUND", IamException.notFound("ACCOUNT_NOT_FOUND", "x"));
        assertStatus(409, "ROLE_IN_USE", IamException.conflict("ROLE_IN_USE", "x"));
        assertStatus(422, "ROLE_UNAVAILABLE", IamException.unprocessable("ROLE_UNAVAILABLE", "x"));
        assertNull(errors.handle(IamException.conflict("ROLE_IN_USE", "x")).getHeaders().getFirst("Retry-After"));
    }

    // 依赖不可用、提交结果未知与并发冲突可区分；未知异常不泄露消息。
    @Test
    void infrastructureFailuresDoNotLeakDetails() {
        var unavailable = errors.handle(new IamUnavailableException("会话存储暂不可用", new RuntimeException("secret")));
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("SERVICE_UNAVAILABLE", unavailable.getBody().getProperties().get("code"));
        assertEquals("服务暂不可用", unavailable.getBody().getDetail());
        assertStatus(503, "IAM_RESULT_UNKNOWN", new TransactionSystemException("commit failed"));
        assertStatus(503, "SERVICE_UNAVAILABLE", new DataAccessResourceFailureException("db down"));
        assertStatus(409, "VERSION_CONFLICT", new DuplicateKeyException("duplicate"));
        var internal = errors.handle(new IllegalArgumentException("secret detail"));
        assertEquals(500, internal.getStatusCode().value());
        assertEquals("服务处理失败", internal.getBody().getDetail());
    }

    // 领域规则违反由唯一分派点按类别转换，错误码原样保留；通用异常不再被兜底为 400。
    @Test
    void ruleViolationsUseDispatchPoint() {
        assertStatus(400, "ROLE_CODE_INVALID", IamRuleViolation.invalid("ROLE_CODE_INVALID", "角色代码无效"));
        assertStatus(409, "ROLE_SYSTEM_DISABLE", IamRuleViolation.conflict("ROLE_SYSTEM_DISABLE", "系统角色不能停用"));
    }

    private void assertStatus(int status, String code, Exception failure) {
        var response = errors.handle(failure);
        assertEquals(status, response.getStatusCode().value());
        assertEquals(code, response.getBody().getProperties().get("code"));
    }
}
