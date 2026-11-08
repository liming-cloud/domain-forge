package org.domainforge.iam.domain.model.role;

import org.domainforge.iam.domain.IamRuleViolation;
import org.domainforge.iam.domain.model.shared.DisplayName;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.shared.AggregateRoot;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色维护名称、启用状态与权限授予；没有角色继承，也不因组织自动授角。
 *
 * <p>SYSTEM_ADMIN 为受保护系统角色：只显式拥有 IAM 管理权限，不能停用、删除、改码或修改授予。
 */
public record Role(
        long id, String code, String name, boolean enabled, boolean system, long version, Set<PermissionGrant> grants)
        implements AggregateRoot {
    /**
     * 系统管理员角色的固定代码，ID 见 {@link SystemRole#ADMIN_ID}，由数据库基线创建。
     */
    public static final String SYSTEM_ADMIN_CODE = "SYSTEM_ADMIN";

    /**
     * 恢复状态也校验代码、名称、系统标记与授予不重复。
     */
    public Role {
        if (id <= 0 || version < 1 || system != (id == SystemRole.ADMIN_ID))
            throw IamRuleViolation.invalid("ROLE_STATE_INVALID", "角色身份与版本无效");
        requireCode(code);
        name = new DisplayName(name).value();
        if (grants == null || grants.stream().anyMatch(Objects::isNull))
            throw IamRuleViolation.invalid("GRANT_INVALID", "授予无效");
        if (grants.stream().map(PermissionGrant::permission).collect(Collectors.toSet()).size() != grants.size())
            throw IamRuleViolation.invalid("GRANT_DUPLICATE", "同一权限只能授予一次");
        grants = Set.copyOf(grants);
    }

    /**
     * 角色代码进入当前主体合同，格式与合同一致。
     */
    public static void requireCode(String code) {
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_-]{1,63}"))
            throw IamRuleViolation.invalid("ROLE_CODE_INVALID", "角色代码须为2至64个字母、数字、_或-，以字母开头");
    }

    /**
     * 创建启用的普通角色，初始无授予。
     */
    public static Role create(long id, String code, String name) {
        if (id == SystemRole.ADMIN_ID || SYSTEM_ADMIN_CODE.equalsIgnoreCase(code))
            throw IamRuleViolation.invalid("ROLE_SYSTEM_CREATE", "不能创建系统角色");
        return new Role(id, code, name, true, false, 1, Set.of());
    }

    /**
     * 修改名称；代码不可修改。
     */
    public Role rename(String next) {
        String value = new DisplayName(next).value();
        if (name.equals(value)) return this;
        return new Role(id, code, value, enabled, system, version + 1, grants);
    }

    /**
     * 停用后其授予在下一次授权检查即不再生效；系统角色不能停用。
     */
    public Role changeEnabled(boolean state) {
        if (enabled == state) return this;
        if (system) throw IamRuleViolation.conflict("ROLE_SYSTEM_DISABLE", "系统角色不能停用");
        return new Role(id, code, name, state, system, version + 1, grants);
    }

    /**
     * 整体替换授予；与目录的兼容性由调用方用目录事实逐项核对。
     */
    public Role replaceGrants(Set<PermissionGrant> next) {
        if (system) throw IamRuleViolation.conflict("ROLE_SYSTEM_GRANTS", "系统角色的授予不能修改");
        Role changed = new Role(id, code, name, enabled, system, version + 1, next);
        return changed.grants.equals(grants) ? this : changed;
    }

    /**
     * 只有已停用的普通角色可以删除；是否仍被分配由调用方核对。
     */
    public void requireDeletable() {
        if (system || enabled) throw IamRuleViolation.conflict("ROLE_DELETE_NOT_ALLOWED", "只能删除已停用的普通角色");
    }
}
