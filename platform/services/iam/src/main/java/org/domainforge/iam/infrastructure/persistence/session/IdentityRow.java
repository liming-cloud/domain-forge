package org.domainforge.iam.infrastructure.persistence.session;

import org.domainforge.iam.application.port.authentication.CurrentIdentity;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * 身份投影不包含密码摘要，只暴露当前允许决策所需字段。
 */
public record IdentityRow(String accountId, long credentialVersion, String permissions) {
    /**
     * 令牌摘要来自可信调用方，权限集合复制为不可变快照。
     */
    CurrentIdentity toIdentity(String digest) {
        return new CurrentIdentity(
                UUID.fromString(accountId),
                digest,
                credentialVersion,
                permissions.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(permissions.split(","))));
    }
}
