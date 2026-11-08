package org.domainforge.iam.domain.model.shared;

/**
 * 系统管理员角色的固定标识，由数据库基线创建；账号与角色两个聚合都按它判断，不互相引用。
 */
public final class SystemRole {
    /**
     * 系统管理员角色 ID。
     */
    public static final long ADMIN_ID = 1;

    private SystemRole() {
    }
}
