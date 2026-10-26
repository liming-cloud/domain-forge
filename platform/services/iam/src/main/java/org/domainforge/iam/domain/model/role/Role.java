package org.domainforge.iam.domain.model.role;

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
     * 系统管理员角色的固定 ID 与代码，由数据库基线创建。
     */
    public static final long SYSTEM_ADMIN_ID = 1;
    public static final String SYSTEM_ADMIN_CODE = "SYSTEM_ADMIN";

    /**
     * 恢复状态也校验代码、名称、系统标记与授予不重复。
     */
    public Role {
        if (id <= 0 || version < 1 || system != (id == SYSTEM_ADMIN_ID))
            throw new IllegalArgumentException("角色身份与版本无效");
        requireCode(code);
        name = requireName(name);
        if (grants == null || grants.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("授予无效");
        if (grants.stream().map(PermissionGrant::permission).collect(Collectors.toSet()).size() != grants.size())
            throw new IllegalArgumentException("同一权限只能授予一次");
        grants = Set.copyOf(grants);
    }

    /**
     * 角色代码进入当前主体合同，格式与合同一致。
     */
    public static void requireCode(String code) {
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_-]{1,63}"))
            throw new IllegalArgumentException("角色代码须为2至64个字母、数字、_或-，以字母开头");
    }

    /**
     * 名称为 1 至 64 个字符，去掉首尾空白。
     */
    public static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 64)
            throw new IllegalArgumentException("名称须为1至64个字符");
        return name.strip();
    }

    /**
     * 创建启用的普通角色，初始无授予。
     */
    public static Role create(long id, String code, String name) {
        if (id == SYSTEM_ADMIN_ID || SYSTEM_ADMIN_CODE.equalsIgnoreCase(code))
            throw new IllegalArgumentException("不能创建系统角色");
        return new Role(id, code, name, true, false, 1, Set.of());
    }

    /**
     * 修改名称；代码不可修改。
     */
    public Role rename(String next) {
        String value = requireName(next);
        if (name.equals(value)) return this;
        return new Role(id, code, value, enabled, system, version + 1, grants);
    }

    /**
     * 停用后其授予在下一次授权检查即不再生效；系统角色不能停用。
     */
    public Role changeEnabled(boolean state) {
        if (enabled == state) return this;
        if (system) throw new IllegalStateException("系统角色不能停用");
        return new Role(id, code, name, state, system, version + 1, grants);
    }

    /**
     * 整体替换授予；与目录的兼容性由调用方用目录事实逐项核对。
     */
    public Role replaceGrants(Set<PermissionGrant> next) {
        if (system) throw new IllegalStateException("系统角色的授予不能修改");
        Role changed = new Role(id, code, name, enabled, system, version + 1, next);
        return changed.grants.equals(grants) ? this : changed;
    }

    /**
     * 只有已停用的普通角色可以删除；是否仍被分配由调用方核对。
     */
    public void requireDeletable() {
        if (system || enabled) throw new IllegalStateException("只能删除已停用的普通角色");
    }
}
