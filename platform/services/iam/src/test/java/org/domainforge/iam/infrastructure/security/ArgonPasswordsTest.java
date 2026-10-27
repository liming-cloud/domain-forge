package org.domainforge.iam.infrastructure.security;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.infrastructure.configuration.PasswordHashingSettings;
import org.junit.jupiter.api.Test;

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
            for (String value :
                    new String[]{" ".repeat(15), "😀".repeat(128), "identity password e\u0301"}) {
                String digest = passwords.encode(value);
                assertTrue(digest.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
                assertTrue(passwords.matches(value, digest));
                assertNotEquals(digest, passwords.encode(value));
            }
            assertFalse(
                    passwords.matches("identity password é", passwords.encode("identity password e\u0301")));
            assertFalse(passwords.matches("nonexistent password", null));
        }
    }

    // 过短、过长、未配对代理字符及随包阻断列表在计算前拒绝，不截断或规范化。
    @Test
    void rejectsInvalidOrBlockedPasswords() {
        try (var passwords =
                     new ArgonPasswords(
                             new PasswordHashingSettings(2, 8, 1000))) {
            for (String value :
                    new String[]{
                            "x".repeat(14), "x".repeat(129), "x".repeat(15) + "\uD800", "passwordpassword"
                    }) {
                assertThrows(IamException.class, () -> passwords.encode(value));
            }
        }
    }
}
