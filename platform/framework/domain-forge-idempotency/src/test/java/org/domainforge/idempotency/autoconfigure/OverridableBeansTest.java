package org.domainforge.idempotency.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 幂等的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(FilterRegistrationBean.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(IdempotencyAutoConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    @Test
    void switchPropertyTurnsFilterOff() {
        var context = start(Map.of("domainforge.idempotency.enabled", "false"), IdempotencyAutoConfiguration.class);
        assertEquals(0, context.getBeanFactory().getBeanNamesForType(FilterRegistrationBean.class, true, false).length);
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        FilterRegistrationBean<?> idempotencyFilter() {
            throw new UnsupportedOperationException();
        }
    }
}
