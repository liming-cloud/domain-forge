package org.domainforge.iam.infrastructure.persistence.session;

import org.domainforge.iam.application.port.authentication.CurrentIdentity;

import java.util.Arrays;
import java.util.Set;

/**
 * 身份投影不包含密码摘要，只暴露当前允许决策所需字段。
 */
public record IdentityRow(long accountId, long credentialVersion, String roles, String permissions) {
    /**
     * 令牌摘要来自可信调用方，角色与权限集合复制为不可变快照。
     */
    CurrentIdentity toIdentity(String digest) {
        return new CurrentIdentity(accountId, digest, credentialVersion, split(permissions), split(roles));
    }

    private static Set<String> split(String value) {
        return value.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(value.split(",")));
    }
}
