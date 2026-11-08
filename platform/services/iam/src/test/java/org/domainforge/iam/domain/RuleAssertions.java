package org.domainforge.iam.domain;

import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 断言领域规则违反的错误码。
 */
public final class RuleAssertions {
    private RuleAssertions() {
    }

    /**
     * 动作必须抛出带指定错误码的 IAM 规则违反。
     *
     * @param code   期望的错误码
     * @param action 被测动作
     */
    public static void assertRule(String code, Executable action) {
        assertEquals(code, assertThrows(IamRuleViolation.class, action).code());
    }
}
