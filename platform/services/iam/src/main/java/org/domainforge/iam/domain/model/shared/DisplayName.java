package org.domainforge.iam.domain.model.shared;

import org.domainforge.iam.domain.IamRuleViolation;
import org.jmolecules.ddd.annotation.ValueObject;

/**
 * 角色、组织、菜单共用的展示名称：1 至 64 个字符，去掉首尾空白。
 *
 * @param value 规范化后的名称
 */
@ValueObject
public record DisplayName(String value) {
    /**
     * 校验并规范化。
     */
    public DisplayName {
        if (value == null || value.isBlank() || value.strip().length() > 64) {
            throw IamRuleViolation.invalid("NAME_INVALID", "名称须为1至64个字符");
        }
        value = value.strip();
    }
}
