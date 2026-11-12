package org.domainforge.iam.application;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.domainforge.web.api.Problems;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 断言应用层拒绝的失败分类；领域规则的错误码用 {@code RuleAssertions}。
 */
public final class FailureAssertions {
    private FailureAssertions() {
    }

    /**
     * 动作必须抛出指定类别的 IAM 应用拒绝。
     *
     * @param kind   期望的类别
     * @param action 被测动作
     * @return 抛出的拒绝，供进一步断言错误码
     */
    public static IamException assertFailure(Kind kind, Executable action) {
        IamException failure = assertThrows(IamException.class, action);
        assertEquals(kind, failure.kind());
        return failure;
    }

    /**
     * 非法输入必须是 400 与 {@code INVALID_REQUEST}，不能变成 500。
     *
     * @param action 被测动作
     */
    public static void assertInvalidRequest(Executable action) {
        IamException failure = assertFailure(Kind.INVALID_REQUEST, action);
        assertEquals("INVALID_REQUEST", failure.code());
        var response = Problems.dispatch(failure);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("INVALID_REQUEST", response.getBody().getProperties().get("code"));
    }
}
