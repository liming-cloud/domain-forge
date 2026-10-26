package org.domainforge.iam.infrastructure.persistence.account;

import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.account.Membership;

import java.util.Set;

/**
 * 账号表投影；角色分配与成员归属分表保存。
 */
public record AccountRow(
        long id, String loginName, String credentialDigest, long credentialVersion, long version, boolean enabled) {
    /**
     * 转换领域快照的主表部分。
     */
    static AccountRow from(Account account) {
        return new AccountRow(
                account.id(),
                account.loginName(),
                account.credentialDigest(),
                account.credentialVersion(),
                account.version(),
                account.enabled());
    }

    /**
     * 与子表一起恢复领域模型，并再次校验不变量。
     */
    Account toDomain(Set<Long> roles, Set<Membership> memberships) {
        return new Account(id, loginName, credentialDigest, credentialVersion, version, enabled, roles, memberships);
    }

    /**
     * 禁止记录携带密码摘要的默认record表示。
     */
    @Override
    public String toString() {
        return "AccountRow[id=" + id + "]";
    }
}
