package org.domainforge.scheduling.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.scheduling.internal.TaskDispatcher;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 调度的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(TaskDispatcher.class, ApplicationRunner.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(SchedulingAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    @Override
    protected Map<String, Object> properties() {
        return Map.of("domainforge.scheduling.enabled", "true");
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        TaskDispatcher userDispatcher() {
            throw new UnsupportedOperationException();
        }

        @Bean
        ApplicationRunner scheduledTaskRegistration() {
            throw new UnsupportedOperationException();
        }
    }
}
