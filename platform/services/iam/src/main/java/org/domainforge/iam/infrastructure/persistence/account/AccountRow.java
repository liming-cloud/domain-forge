package org.domainforge.iam.infrastructure.persistence.account;

import org.domainforge.iam.domain.model.Account;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * 账号表投影；权限存储编码不传播到领域或HTTP。
 */
public record AccountRow(
        long id,
        String loginName,
        String credentialDigest,
        long credentialVersion,
        long version,
        boolean enabled,
        String permissions) {
    /**
     * 转换领域快照为确定排序的有限权限表示。
     */
    static AccountRow from(Account account) {
        return new AccountRow(
                account.id(),
                account.loginName(),
                account.credentialDigest(),
                account.credentialVersion(),
                account.version(),
                account.enabled(),
                String.join(",", new TreeSet<>(account.permissions())));
    }

    /**
     * 恢复领域模型再次校验权限目录，不默许数据库异常值。
     */
    Account toDomain() {
        return new Account(
                id,
                loginName,
                credentialDigest,
                credentialVersion,
                version,
                enabled,
                permissions.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(permissions.split(","))));
    }

    /**
     * 禁止记录携带密码摘要的默认record表示。
     */
    @Override
    public String toString() {
        return "AccountRow[id=" + id + "]";
    }
}
