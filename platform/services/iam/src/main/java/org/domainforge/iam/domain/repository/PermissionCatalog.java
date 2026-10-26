package org.domainforge.iam.domain.repository;

import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.domainforge.iam.domain.model.permission.PermissionNamespace;

import java.util.Collection;
import java.util.Map;

/**
 * 权限目录仓储；安装须先取得目录协调锁，授予与菜单引用目录时取共享锁。
 */
public interface PermissionCatalog {
    /**
     * 目录总量上限，保证当前主体的权限集合有界。
     */
    int MAX_TOTAL = 1000;

    /**
     * 目录协调锁：exclusive 用于安装，否则为共享锁。
     */
    void lock(boolean exclusive);

    /**
     * 按权限码读取定义，缺失的码不出现在结果中。
     */
    Map<String, PermissionDefinition> find(Collection<String> codes);

    /**
     * 读取命名空间当前内容；未安装返回空集合的命名空间。
     */
    PermissionNamespace namespace(String namespace);

    /**
     * 其他命名空间的权限总数。
     */
    long countOutside(String namespace);

    /**
     * 被角色授予或菜单引用的权限码中，属于给定集合的部分。
     */
    java.util.Set<String> referenced(Collection<String> codes);

    /**
     * 整体替换命名空间内容。
     */
    void replace(PermissionNamespace namespace);
}
