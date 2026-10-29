package org.domainforge.notification.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.notification.api.SmsMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 手动真实发送：只在设置了 {@code ALIYUN_SMS_LIVE_PHONE} 时运行（CI 不设置），用运维提供的账号、签名与模板
 * 向指定号码发送一条短信，确认签名、参数与应答解析在真实服务上可用。用法见 docs/architecture/12-短信与验证码.md。
 */
@EnabledIfEnvironmentVariable(named = "ALIYUN_SMS_LIVE_PHONE", matches = ".+")
class AliyunSmsLiveIT {
    @Test
    void sendsRealMessage() {
        var settings = new AliyunSmsSender.Settings(URI.create("https://dysmsapi.aliyuncs.com"),
                System.getenv("ALIYUN_SMS_ACCESS_KEY_ID"), System.getenv("ALIYUN_SMS_ACCESS_KEY_SECRET"),
                System.getenv("ALIYUN_SMS_SIGN_NAME"), Map.of("live", System.getenv("ALIYUN_SMS_TEMPLATE_CODE")),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        var receipt = new AliyunSmsSender(settings, new SimpleMeterRegistry(), Clock.systemUTC()).send(
                new SmsMessage(System.getenv("ALIYUN_SMS_LIVE_PHONE"), "live", Map.of(
                        System.getenv().getOrDefault("ALIYUN_SMS_TEMPLATE_PARAM", "code"), "246810")));
        assertFalse(receipt.messageId().isBlank());
        System.out.println("aliyun_sms_live=sent biz_id=" + receipt.messageId());
    }
}
