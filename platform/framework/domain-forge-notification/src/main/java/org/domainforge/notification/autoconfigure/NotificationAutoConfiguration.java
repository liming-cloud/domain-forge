package org.domainforge.notification.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.notification.api.SmsSender;
import org.domainforge.notification.api.VerificationCodes;
import org.domainforge.notification.internal.NotificationAssembly;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 通知装配：{@code domainforge.sms.enabled=true} 启用短信发送；在此基础上 {@code domainforge.verification.enabled=true}
 * 启用验证码（使用服务的会话 Redis）。凭据与密钥只来自环境变量。
 * 默认服务商为阿里云；其他服务商由业务提供 {@link SmsSender} Bean 接入，不再需要阿里云配置。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "domainforge.sms.enabled", havingValue = "true")
public class NotificationAutoConfiguration {
    /**
     * 默认短信发送（阿里云）；业务提供自己的 {@link SmsSender} Bean 接入其他服务商时让位。
     */
    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    SmsSender smsSender(Environment environment, ObjectProvider<MeterRegistry> metrics) {
        return NotificationAssembly.sms(environment, metrics.getIfAvailable(SimpleMeterRegistry::new));
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
        @ConditionalOnMissingBean
        VerificationCodes verificationCodes(Environment environment, RedissonClient redis, SmsSender sms) {
            return NotificationAssembly.verification(environment, redis, sms);
        }
    }
}
