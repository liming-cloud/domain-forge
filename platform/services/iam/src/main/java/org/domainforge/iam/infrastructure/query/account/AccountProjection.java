package org.domainforge.iam.infrastructure.query.account;

import org.domainforge.iam.application.query.account.AccountView;

/**
 * 数据库投影只包含公开查询字段，查询SQL不读取凭据。
 */
public record AccountProjection(long id, String loginName, boolean enabled, long version) {
    /**
     * 转为技术无关只读视图。
     */
    AccountView view() {
        return new AccountView(id, loginName, enabled, version);
    }
}
