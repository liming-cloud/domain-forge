package org.domainforge.iam.domain.model.permission;

import org.domainforge.shared.AggregateRoot;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个服务发布的权限命名空间，整体安装或升级；IAM 自身命名空间不可安装。
 *
 * @param namespace   命名空间
 * @param permissions 该命名空间的全部权限
 */
public record PermissionNamespace(String namespace, Set<PermissionDefinition> permissions) implements AggregateRoot {
    /**
     * 单个命名空间最多包含的权限数；整个目录另有总量上限。
     */
    public static final int MAX_PERMISSIONS = 200;

    /**
     * 每个权限都属于本命名空间、不重复，且外部服务不能声明系统专属权限。
     */
    public PermissionNamespace {
        PermissionDefinition.requireNamespace(namespace);
        if (PermissionDefinition.IAM_NAMESPACE.equals(namespace))
            throw new IllegalArgumentException("IAM 命名空间由数据库基线维护");
        if (permissions == null
                || permissions.stream().anyMatch(Objects::isNull)
                || permissions.size() > MAX_PERMISSIONS)
            throw new IllegalArgumentException("一个命名空间须包含至多200个权限");
        Set<String> codes = permissions.stream().map(PermissionDefinition::code).collect(Collectors.toSet());
        if (codes.size() != permissions.size()) throw new IllegalArgumentException("权限码重复");
        for (PermissionDefinition permission : permissions) {
            if (!permission.namespace().equals(namespace))
                throw new IllegalArgumentException("权限码须属于本命名空间");
            if (permission.systemOnly()) throw new IllegalArgumentException("外部命名空间不能声明系统专属权限");
        }
        permissions = Set.copyOf(permissions);
    }
}
