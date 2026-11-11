package org.domainforge.iam.application;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
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
     * 动作必须抛出指定分类的 IAM 应用失败。
     *
     * @param failure 期望的失败分类
     * @param action  被测动作
     */
    public static void assertFailure(IamFailure failure, Executable action) {
        assertEquals(failure, assertThrows(IamException.class, action).failure());
    }
}
