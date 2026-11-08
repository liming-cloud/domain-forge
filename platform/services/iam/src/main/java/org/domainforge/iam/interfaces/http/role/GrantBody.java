package org.domainforge.iam.interfaces.http.role;

import org.domainforge.iam.application.command.role.GrantInput;

import java.util.HashSet;
import java.util.List;

/**
 * 一项授予；请求与响应共用。组织 ID 以十进制字符串传输。
 *
 * @param permission 权限码
 * @param scope      数据范围
 * @param unitIds    自定义范围的组织
 */
public record GrantBody(String permission, String scope, List<String> unitIds) {
    /**
     * 缺少组织列表视为空列表。
     */
    public GrantBody {
        unitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
    }

    /**
     * 转为应用输入；范围名称与组合在应用与领域规则中校验。
     */
    GrantInput toInput() {
        var units = new HashSet<Long>();
        for (String unit : unitIds) units.add(Long.parseLong(unit));
        return new GrantInput(permission, scope, units);
    }
}
