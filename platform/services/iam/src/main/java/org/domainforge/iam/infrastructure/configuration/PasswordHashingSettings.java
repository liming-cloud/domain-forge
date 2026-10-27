package org.domainforge.iam.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 密码计算工作池的资源边界；决定线程池与队列容量，变更随服务重启生效，越界值阻止启动。
 *
 * @param workers       并发计算线程数
 * @param queueCapacity 等待队列容量
 * @param waitMillis    排队等待上限
 */
@ConfigurationProperties(prefix = "iam.password-hashing", ignoreUnknownFields = false)
public record PasswordHashingSettings(int workers, int queueCapacity, int waitMillis) {
    /**
     * 只允许减少资源，缺失或越界直接阻止启动。
     */
    public PasswordHashingSettings {
        LoginProtectionSettings.range("workers", workers, 1, 2);
        LoginProtectionSettings.range("queue-capacity", queueCapacity, 1, 8);
        LoginProtectionSettings.range("wait-millis", waitMillis, 100, 1000);
    }
}
