package org.domainforge.iam.domain.model.shared;

import org.domainforge.iam.domain.IamRuleViolation;
import org.jmolecules.ddd.annotation.ValueObject;

import java.util.Locale;

/**
 * 登录名：3 至 64 个 ASCII 字母、数字或 {@code ._-}，规范化为小写；不修剪空白。
 *
 * @param value 规范化后的登录名
 */
@ValueObject
public record LoginName(String value) {
    /**
     * 校验并规范化大小写。
     */
    public LoginName {
        if (value == null || !value.matches("[a-zA-Z0-9._-]{3,64}")) {
            throw IamRuleViolation.invalid("LOGIN_NAME_INVALID", "登录名必须为3至64个ASCII字母、数字或._-");
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
