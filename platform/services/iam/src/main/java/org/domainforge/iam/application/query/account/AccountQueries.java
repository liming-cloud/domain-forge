package org.domainforge.iam.application.query.account;

/**
 * 账号查询合同，实现直接使用本上下文 SQL 投影。
 */
public interface AccountQueries {
    /**
     * 当前可信主体的最小资料；调用方不能传入任意账号。
     */
    AccountView self(long trustedAccountId);

    /**
     * 按 ID 升序的键分页，limit 为 1 至 100。
     */
    AccountPage list(long after, int limit);

    /**
     * 账号详情，不存在返回 null。
     */
    AccountDetail detail(long id);
}
