package org.domainforge.notification.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.notification.api.SmsSender;
import org.domainforge.notification.api.VerificationCodes;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按配置组装短信发送与验证码；缺少必需配置时启动失败。
 */
public final class NotificationAssembly {
    private static final String SMS = "domainforge.sms.";
    private static final String VERIFICATION = "domainforge.verification.";

    private NotificationAssembly() {
    }

    /**
     * 短信发送：目前只有阿里云实现（{@code domainforge.sms.provider=aliyun}）。
     */
    public static SmsSender sms(Environment environment, MeterRegistry metrics) {
        String provider = environment.getProperty(SMS + "provider", "aliyun");
        if (!provider.equals("aliyun")) throw new IllegalStateException("domainforge.sms.provider 只支持 aliyun");
        Map<String, String> templates = Binder.get(environment)
                .bind(SMS + "templates", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        var settings = new AliyunSmsSender.Settings(
                URI.create(environment.getProperty(SMS + "aliyun.endpoint", "https://dysmsapi.aliyuncs.com")),
                required(environment, SMS + "aliyun.access-key-id"),
                required(environment, SMS + "aliyun.access-key-secret"),
                required(environment, SMS + "sign-name"), templates,
                environment.getProperty(SMS + "connect-timeout", Duration.class, Duration.ofSeconds(2)),
                environment.getProperty(SMS + "request-timeout", Duration.class, Duration.ofSeconds(5)));
        return new AliyunSmsSender(settings, metrics, Clock.systemUTC());
    }

    /**
     * 验证码：密钥 {@code domainforge.verification.key}（Base64，至少 32 字节）与各用途的策略。
     */
    public static VerificationCodes verification(Environment environment, RedissonClient redis, SmsSender sms) {
        byte[] key = Base64.getDecoder().decode(required(environment, VERIFICATION + "key"));
        if (key.length < 32) throw new IllegalStateException("domainforge.verification.key 至少 32 字节");
        Map<String, Object> purposes = Binder.get(environment)
                .bind(VERIFICATION + "purposes", Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        Map<String, RedisVerificationCodes.Policy> policies = new LinkedHashMap<>();
        for (String purpose : purposes.keySet()) policies.put(purpose, policy(environment, purpose));
        if (policies.isEmpty()) throw new IllegalStateException("启用了验证码，但未声明任何用途");
        return new RedisVerificationCodes(redis, sms, policies, key, Clock.systemDefaultZone());
    }

    private static RedisVerificationCodes.Policy policy(Environment environment, String purpose) {
        String prefix = VERIFICATION + "purposes." + purpose + ".";
        return new RedisVerificationCodes.Policy(
                required(environment, prefix + "template"),
                environment.getProperty(prefix + "parameter", "code"),
                environment.getProperty(prefix + "length", Integer.class, 6),
                environment.getProperty(prefix + "ttl", Duration.class, Duration.ofMinutes(5)),
                environment.getProperty(prefix + "max-attempts", Integer.class, 5),
                environment.getProperty(prefix + "resend-interval", Duration.class, Duration.ofSeconds(60)),
                environment.getProperty(prefix + "daily-limit", Integer.class, 10));
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name, "");
        if (value.isBlank()) throw new IllegalStateException("通知组件缺少配置 " + name);
        return value;
    }
}
