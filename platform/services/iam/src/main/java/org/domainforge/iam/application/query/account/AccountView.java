package org.domainforge.iam.application.query.account;

/**
 * 最小账号投影，不含密码摘要、会话或组织假数据。
 */
public record AccountView(long id, String loginName, boolean enabled, long version) {
}
