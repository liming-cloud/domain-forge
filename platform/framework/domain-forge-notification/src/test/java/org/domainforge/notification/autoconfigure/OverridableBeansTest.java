package org.domainforge.notification.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.notification.api.SmsSender;
import org.domainforge.notification.api.VerificationCodes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 通知的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(SmsSender.class, VerificationCodes.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(NotificationAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    @Override
    protected Map<String, Object> properties() {
        return Map.of("domainforge.sms.enabled", "true", "domainforge.verification.enabled", "true");
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        SmsSender userSender() {
            throw new UnsupportedOperationException();
        }

        @Bean
        VerificationCodes userCodes() {
            throw new UnsupportedOperationException();
        }
    }
}
