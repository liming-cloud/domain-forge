package org.domainforge.notification.api;

import java.time.Instant;

/**
 * 已发送的验证码：只返回时间，不返回验证码本身。
 *
 * @param expiresAt 过期时间
 * @param resendAt  最早可重发时间
 */
public record IssuedCode(Instant expiresAt, Instant resendAt) {
}
