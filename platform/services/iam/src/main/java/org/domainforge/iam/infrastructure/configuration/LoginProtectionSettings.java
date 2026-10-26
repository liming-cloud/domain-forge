package org.domainforge.iam.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录防护与密码计算的有限部署输入；配置变更随服务重启生效，越界值阻止启动。
 */
@ConfigurationProperties(prefix = "iam.login-protection", ignoreUnknownFields = false)
public record LoginProtectionSettings(
        int accountAttempts,
        int accountWindowSeconds,
        int addressAttempts,
        int addressWindowSeconds,
        int hashWorkers,
        int hashQueueCapacity,
        int hashWaitMillis) {
    /**
     * 全部范围只允许收紧保护或减少资源，缺失或越界直接阻止启动。
     */
    public LoginProtectionSettings {
        range("account-attempts", accountAttempts, 1, 5);
        range("account-window-seconds", accountWindowSeconds, 600, 3600);
        range("address-attempts", addressAttempts, 1, 30);
        range("address-window-seconds", addressWindowSeconds, 60, 600);
        range("hash-workers", hashWorkers, 1, 2);
        range("hash-queue-capacity", hashQueueCapacity, 1, 8);
        range("hash-wait-millis", hashWaitMillis, 100, 1000);
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException("登录防护配置越界: " + name);
    }
}
