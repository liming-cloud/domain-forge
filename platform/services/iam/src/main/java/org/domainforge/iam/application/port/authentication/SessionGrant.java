package org.domainforge.iam.application.port.authentication;

import java.time.Instant;

/**
 * 待登记的 SQL 会话许可：令牌摘要、账号、签发时的凭据版本和到期时间。
 *
 * <p>会话是否仍然有效由 SQL 当前状态判断（未撤销、未过期、账号启用、凭据版本一致），不在内存中推断。
 */
public record SessionGrant(String tokenDigest, long accountId, long credentialVersion, Instant expiresAt) {
    /**
     * 拒绝无效摘要、身份、版本或期限。
     */
    public SessionGrant {
        if (tokenDigest == null
                || !tokenDigest.matches("[a-f0-9]{64}")
                || accountId <= 0
                || credentialVersion < 1
                || expiresAt == null) throw new IllegalArgumentException("会话许可无效");
    }

    /**
     * 不将摘要记录到默认日志表示。
     */
    @Override
    public String toString() {
        return "SessionGrant[accountId=" + accountId + "]";
    }
}
