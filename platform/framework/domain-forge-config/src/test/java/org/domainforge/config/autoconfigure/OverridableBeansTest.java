package org.domainforge.config.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.config.api.DynamicSettingsBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 动态配置的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(DynamicSettingsBinder.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(ConfigConfiguration.class);
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
        DynamicSettingsBinder userBinder() {
            throw new UnsupportedOperationException();
        }
    }
}
