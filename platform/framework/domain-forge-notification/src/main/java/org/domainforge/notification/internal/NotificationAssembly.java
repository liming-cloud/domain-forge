package org.domainforge.notification.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.notification.api.SmsProperties;
import org.domainforge.notification.api.SmsSender;
import org.domainforge.notification.api.VerificationCodes;
import org.domainforge.notification.api.VerificationProperties;
import org.redisson.api.RedissonClient;

import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按配置组装短信发送与验证码；缺少必需配置时启动失败。
 */
public final class NotificationAssembly {
    private NotificationAssembly() {
    }

    /**
     * 短信发送：默认实现为阿里云（{@code domainforge.sms.provider=aliyun}）；其他服务商经 {@link SmsSender} Bean 接入。
     */
    public static SmsSender sms(SmsProperties properties, MeterRegistry metrics) {
        if (!properties.provider().equals("aliyun")) {
            throw new IllegalStateException("domainforge.sms.provider 只支持 aliyun");
        }
        var aliyun = properties.aliyun();
        var settings = new AliyunSmsSender.Settings(aliyun.endpoint(),
                required(aliyun.accessKeyId(), "domainforge.sms.aliyun.access-key-id"),
                required(aliyun.accessKeySecret(), "domainforge.sms.aliyun.access-key-secret"),
                required(properties.signName(), "domainforge.sms.sign-name"), properties.templates(),
                properties.connectTimeout(), properties.requestTimeout());
        return new AliyunSmsSender(settings, metrics, Clock.systemUTC());
    }

    /**
     * 验证码：密钥 {@code domainforge.verification.key}（Base64，至少 32 字节）与各用途的策略。
     */
    public static VerificationCodes verification(VerificationProperties properties, RedissonClient redis,
            SmsSender sms) {
        byte[] key = Base64.getDecoder().decode(required(properties.key(), "domainforge.verification.key"));
        if (key.length < 32) throw new IllegalStateException("domainforge.verification.key 至少 32 字节");
        Map<String, RedisVerificationCodes.Policy> policies = new LinkedHashMap<>();
        properties.purposes().forEach((purpose, value) -> policies.put(purpose, policy(purpose, value)));
        if (policies.isEmpty()) throw new IllegalStateException("启用了验证码，但未声明任何用途");
        return new RedisVerificationCodes(redis, sms, policies, key, Clock.systemDefaultZone());
    }

    private static RedisVerificationCodes.Policy policy(String purpose, VerificationProperties.Purpose value) {
        return new RedisVerificationCodes.Policy(
                required(value.template(), "domainforge.verification.purposes." + purpose + ".template"),
                value.parameter(), value.length(), value.ttl(), value.maxAttempts(), value.resendInterval(),
                value.dailyLimit());
    }

    private static String required(String value, String name) {
        if (value.isBlank()) throw new IllegalStateException("通知组件缺少配置 " + name);
        return value;
    }
}
