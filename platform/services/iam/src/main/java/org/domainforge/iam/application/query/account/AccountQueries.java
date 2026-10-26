package org.domainforge.iam.application.query.account;

/**
 * 纯查询短路径，公开合同不泄露 Mapper、PO 或领域凭据。
 */
public interface AccountQueries {
    /**
     * id 必须来自入口已经验证的当前主体；不提供"按任意 ID 读本人"的接口。
     */
    AccountView self(long trustedAccountId);

    /**
     * 入口必须已验证 iam:account:read；after 为上一页游标（首页为 0），limit 为 1 至 100。
     */
    AccountPage list(long after, int limit);
}
