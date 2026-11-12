package org.domainforge.messaging.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.messaging.api.EventPublisher;
import org.domainforge.messaging.internal.EventConsumers;
import org.domainforge.messaging.internal.OutboxRelay;
import org.domainforge.messaging.internal.TraceContexts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 消息的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(MessagingAutoConfiguration.MessagingClient.class, EventPublisher.class, OutboxRelay.class,
                EventConsumers.class, TraceContexts.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(MessagingAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        MessagingAutoConfiguration.MessagingClient userClient() {
            throw new UnsupportedOperationException();
        }

        @Bean
        EventPublisher userPublisher() {
            throw new UnsupportedOperationException();
        }

        @Bean
        OutboxRelay userRelay() {
            throw new UnsupportedOperationException();
        }

        @Bean
        EventConsumers userConsumers() {
            throw new UnsupportedOperationException();
        }

        @Bean
        TraceContexts userTraces() {
            throw new UnsupportedOperationException();
        }
    }
}
