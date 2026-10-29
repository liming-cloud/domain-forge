package org.domainforge.notification.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.notification.api.SmsSender;
import org.domainforge.notification.api.VerificationCodes;
import org.domainforge.notification.internal.NotificationAssembly;
import org.domainforge.notification.internal.NotificationFailureAdvice;
import org.domainforge.notification.internal.SmsRejectionMapping;
import org.domainforge.notification.internal.VerificationRejectionMapping;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 通知装配：{@code domainforge.sms.enabled=true} 启用短信发送；在此基础上 {@code domainforge.verification.enabled=true}
 * 启用验证码（使用服务的会话 Redis）。凭据与密钥只来自环境变量。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "domainforge.sms.enabled", havingValue = "true")
public class NotificationAutoConfiguration {
    /**
     * 短信发送。
     */
    @Bean
    SmsSender smsSender(Environment environment, ObjectProvider<MeterRegistry> metrics) {
        return NotificationAssembly.sms(environment, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 服务商不可用、验证码存储不可用映射为 503。
     */
    @Bean
    NotificationFailureAdvice notificationFailureAdvice() {
        return new NotificationFailureAdvice();
    }

    /**
     * 短信拒绝映射为业务拒绝。
     */
    @Bean
    SmsRejectionMapping smsRejectionMapping() {
        return new SmsRejectionMapping();
    }

    /**
     * 验证码。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "domainforge.verification.enabled", havingValue = "true")
    static class Verification {
        /**
         * 验证码存取。
         */
        @Bean
        VerificationCodes verificationCodes(Environment environment, RedissonClient redis, SmsSender sms) {
            return NotificationAssembly.verification(environment, redis, sms);
        }

        /**
         * 发送受限映射为业务拒绝。
         */
        @Bean
        VerificationRejectionMapping verificationRejectionMapping() {
            return new VerificationRejectionMapping();
        }
    }
}
