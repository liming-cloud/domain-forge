package org.domainforge.iam.domain.repository;

import org.domainforge.iam.domain.model.role.Role;

/**
 * 角色聚合仓储（含授予与自定义范围组织）。
 */
public interface RoleRepository {
    /**
     * 锁定角色直到事务结束，不存在返回 null。
     */
    Role lock(long id);

    /**
     * 角色代码是否已被使用（不区分大小写）。
     */
    boolean codeTaken(String code);

    /**
     * 创建角色；代码唯一由数据库兜底。
     */
    void insert(Role role);

    /**
     * 按原版本保存名称、状态与授予，零行报告冲突。
     */
    void update(Role role, long expectedVersion);

    /**
     * 删除角色及其授予。
     */
    void delete(long id);

    /**
     * 角色当前分配给多少账号。
     */
    long assignments(long id);
}
