package org.domainforge.persistence.autoconfigure;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 持久化的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(MybatisPlusInterceptor.class, FlywayMigrationStrategy.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(PersistenceAutoConfiguration.class);
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
        MybatisPlusInterceptor userPagination() {
            throw new UnsupportedOperationException();
        }

        @Bean
        FlywayMigrationStrategy userMigration() {
            throw new UnsupportedOperationException();
        }
    }
}
