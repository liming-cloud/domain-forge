package org.domainforge.iam.domain.model.permission;

import org.domainforge.shared.ValueObject;

/**
 * 权限目录中的一个精确操作码；只有目录中的权限可以授予，不存在通配符。
 *
 * @param code       形如 {@code namespace:resource:action} 的权限码
 * @param name       展示名称
 * @param scoped     授予时是否需要数据范围
 * @param systemOnly 是否只能授予系统角色（仅 IAM 自身管理权限）
 */
public record PermissionDefinition(String code, String name, boolean scoped, boolean systemOnly)
        implements ValueObject {
    /**
     * IAM 自身命名空间由数据库基线维护，不能通过安装接口修改。
     */
    public static final String IAM_NAMESPACE = "iam";

    /**
     * 校验格式、名称，以及系统专属权限不需要数据范围。
     */
    public PermissionDefinition {
        requireCode(code);
        if (name == null || name.isBlank() || name.strip().length() > 64)
            throw new IllegalArgumentException("权限名称须为1至64个字符");
        name = name.strip();
        if (scoped && systemOnly) throw new IllegalArgumentException("系统专属权限不需要数据范围");
    }

    /**
     * 权限码为命名空间加 1 至 3 段，全部小写。
     */
    public static void requireCode(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9-]{1,31}(:[a-z][a-z0-9-]{0,47}){1,3}") || code.length() > 128)
            throw new IllegalArgumentException("权限码格式无效");
    }

    /**
     * 命名空间为 2 至 32 个小写字母、数字或 -。
     */
    public static void requireNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{1,31}"))
            throw new IllegalArgumentException("命名空间格式无效");
    }

    /**
     * 权限码的第一段。
     */
    public String namespace() {
        return code.substring(0, code.indexOf(':'));
    }
}
