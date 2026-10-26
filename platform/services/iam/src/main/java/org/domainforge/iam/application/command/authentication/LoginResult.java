package org.domainforge.iam.application.command.authentication;

/**
 * 原生令牌仅在SQL提交确认后交付；不得记录到日志或幂等收据。
 */
public record LoginResult(String token, long expiresInSeconds) {
    /**
     * 禁止默认字符串表示泄露令牌。
     */
    @Override
    public String toString() {
        return "LoginResult[redacted]";
    }
}
