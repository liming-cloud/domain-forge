package org.domainforge.iam.infrastructure.persistence.account;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 账号持久化SQL仅以绑定值访问具体账号，管理守卫由应用提前取得。
 */
@org.apache.ibatis.annotations.Mapper
public interface AccountMapper {
    /**
     * 返回指定账号当前存储状态，不存在返回null。
     */
    AccountRow find(long id);

    /**
     * 登录名已由领域规则规范化，不在SQL拼接外部输入。
     */
    AccountRow byName(String name);

    /**
     * 锁定账号直到当前事务结束。
     */
    AccountRow lock(long id);

    /**
     * 唯一键拒绝同名账号，包括并发注册。
     */
    int insert(AccountRow row);

    /**
     * 乐观版本条件同时保护意外漏锁调用。
     */
    int update(@Param("row") AccountRow row, @Param("expected") long expected);

    List<Long> roles(long account);

    List<MembershipRow> memberships(long account);

    int deleteRoles(long account);

    int insertRoles(@Param("account") long account, @Param("roles") List<Long> roles);

    int deleteMemberships(long account);

    int insertMemberships(@Param("account") long account, @Param("items") List<MembershipRow> items);

    /**
     * 启用且持有系统管理员角色的账号数。
     */
    long systemAdministrators(long role);
}
