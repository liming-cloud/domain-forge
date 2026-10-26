package org.domainforge.iam.application.port.authentication;

import java.util.Set;

/**
 * 本次有效会话与账号当前状态的交集，不作为跨请求授权缓存。
 */
public record CurrentIdentity(
        long accountId, String tokenDigest, long credentialVersion, Set<String> permissions, Set<String> roles) {
    /**
     * 防止调用方通过修改集合扩大已验证权限。
     */
    public CurrentIdentity {
        permissions = Set.copyOf(permissions);
        roles = Set.copyOf(roles);
    }

    /**
     * 本次检查所见的账号是否持有启用的系统管理员角色。
     */
    public boolean systemAdministrator() {
        return roles.contains("SYSTEM_ADMIN");
    }

    /**
     * 诊断信息不打印令牌摘要。
     */
    @Override
    public String toString() {
        return "CurrentIdentity[accountId=" + accountId + "]";
    }
}
