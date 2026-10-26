package org.domainforge.iam.domain.repository;

import org.domainforge.iam.domain.model.account.Account;

/**
 * 账号聚合仓储（含角色分配与成员归属），账号不存在时读取返回null，写入冲突不可当成功。
 */
public interface AccountRepository {
    /**
     * 读取当前账号；不得从跨请求缓存恢复允许依据。
     */
    Account find(long id);

    /**
     * 读取用于密码校验的账号快照，密码计算后仍须再次锁定核对版本。
     */
    Account findByLoginName(String normalizedName);

    /**
     * 在调用方事务中取得账号排他锁；可能移除系统管理员的操作须先持有管理守卫。
     */
    Account lock(long id);

    /**
     * 创建新账号及其角色分配；唯一登录名由数据库兜底。
     */
    void insert(Account account);

    /**
     * 仅按原版本保存转换结果（含角色与成员归属），零行必须报告冲突。
     */
    void update(Account account, long expectedVersion);

    /**
     * 启用且持有系统管理员角色的账号数；须在管理守卫内使用。
     */
    long systemAdministrators();
}
