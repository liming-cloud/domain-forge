package org.domainforge.notification.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * 验证码配置（前缀 {@code domainforge.verification}）。
 *
 * @param enabled  设为 true 启用验证码
 * @param key      Base64 编码的密钥，至少 32 字节
 * @param purposes 各用途的策略，键为用途名
 */
@ConfigurationProperties("domainforge.verification")
public record VerificationProperties(
        boolean enabled,
        @DefaultValue("") String key,
        Map<String, Purpose> purposes) {
    /**
     * 未声明用途时为空映射，由装配拒绝。
     */
    public VerificationProperties {
        if (purposes == null) purposes = Map.of();
    }

    /**
     * 一种用途的策略。
     *
     * @param template       短信模板名
     * @param parameter      验证码在模板中的参数名
     * @param length         验证码位数
     * @param ttl            有效期
     * @param maxAttempts    最多校验次数
     * @param resendInterval 重发间隔
     * @param dailyLimit     每个号码每日上限
     */
    public record Purpose(
            @DefaultValue("") String template,
            @DefaultValue("code") String parameter,
            @DefaultValue("6") int length,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("60s") Duration resendInterval,
            @DefaultValue("10") int dailyLimit) {
    }
}
