package org.domainforge.iam.domain.model.role;

import org.domainforge.iam.domain.IamRuleViolation;
import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.jmolecules.ddd.annotation.ValueObject;

import java.util.Objects;
import java.util.Set;

/**
 * 角色对一个精确权限的授予，权限与范围绑定；CUSTOM_DEPARTMENTS 只包含列出的组织，不隐含子树。
 *
 * @param permission 权限码
 * @param scope      数据范围
 * @param unitIds    自定义范围的组织 ID，其他范围为空
 */
@ValueObject
public record PermissionGrant(String permission, DataScope scope, Set<Long> unitIds) {
    /**
     * 自定义范围一次授予最多列出的组织数。
     */
    public static final int MAX_UNITS = 1000;

    /**
     * 自定义范围须列出 1 至 1000 个组织，其他范围不能携带组织。
     */
    public PermissionGrant {
        PermissionDefinition.requireCode(permission);
        if (scope == null || unitIds == null || unitIds.stream().anyMatch(Objects::isNull))
            throw IamRuleViolation.invalid("GRANT_INVALID", "授予无效");
        boolean custom = scope == DataScope.CUSTOM_DEPARTMENTS;
        if (custom ? unitIds.isEmpty() || unitIds.size() > MAX_UNITS : !unitIds.isEmpty())
            throw IamRuleViolation.invalid("GRANT_UNITS_INVALID", "自定义范围须列出1至1000个组织，其他范围不能列出组织");
        if (unitIds.stream().anyMatch(id -> id <= 0)) throw IamRuleViolation.invalid("UNIT_ID_INVALID", "组织 ID 无效");
        unitIds = Set.copyOf(unitIds);
    }

    /**
     * 按目录定义核对：数据操作必须给出实际范围，非数据操作只能 NOT_APPLICABLE；仅系统角色可持有系统专属权限。
     */
    public void requireCompatible(PermissionDefinition definition, boolean systemRole) {
        if (definition == null || !definition.code().equals(permission))
            throw IamRuleViolation.invalid("GRANT_PERMISSION_UNKNOWN", "权限不在目录中");
        if (definition.scoped() == (scope == DataScope.NOT_APPLICABLE))
            throw IamRuleViolation.invalid("GRANT_SCOPE_MISMATCH", "数据范围与权限目录声明不符");
        if (definition.systemOnly() && !systemRole)
            throw IamRuleViolation.invalid("GRANT_SYSTEM_ONLY", "系统专属权限不能授予普通角色");
    }
}
