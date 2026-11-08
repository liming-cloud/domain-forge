package org.domainforge.iam.infrastructure.security;

import org.domainforge.iam.infrastructure.configuration.PasswordHashingSettings;
import org.junit.jupiter.api.Test;

import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 对真实Argon2id验证边界与原文匹配，不以伪摘要或耗时估算替代行为。
 */
class ArgonPasswordsTest {
    // 随机盐、自描述安全参数、空白和补充平面Unicode均保留原输入语义。
    @Test
    void hashesOriginalUnicodeWithRandomSalt() {
        try (var passwords =
                new ArgonPasswords(
                        new PasswordHashingSettings(2, 8, 1000))) {
            for (String value : new String[]{"x".repeat(14), "x".repeat(129), "x".repeat(15) + "\uD800"}) {
                assertRule("PASSWORD_LENGTH_INVALID", () -> passwords.encode(value));
            }
            assertRule("PASSWORD_COMMON", () -> passwords.encode("passwordpassword"));
        }
    }
}
