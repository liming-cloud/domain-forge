package org.domainforge.cache.autoconfigure;

import org.domainforge.buildrules.testsupport.OverridableBeansContract;
import org.domainforge.cache.api.CacheOperations;
import org.domainforge.cache.api.LockOperations;
import org.domainforge.cache.api.RedisScriptOperations;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 缓存的公开 Bean 都可以被业务的 Bean 替换；业务不提供时回归默认实现。
 */
class OverridableBeansTest extends OverridableBeansContract {
    @Override
    protected List<Class<?>> types() {
        return List.of(CacheOperations.class, LockOperations.class, RedisScriptOperations.class);
    }

    @Override
    protected List<Class<?>> configurations() {
        return List.of(Redis.class, CacheOperationsConfiguration.class);
    }

    @Override
    protected Class<?> user() {
        return User.class;
    }

    /**
     * 默认缓存 Bean 以 Redisson 客户端存在为前提；只提供定义。
     */
    @Configuration(proxyBeanMethods = false)
    static class Redis {
        @Bean
        RedissonClient redisson() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 业务提供的 Bean；只检查定义，不会被创建。
     */
    @Configuration(proxyBeanMethods = false)
    static class User {
        @Bean
        CacheOperations userCache() {
            throw new UnsupportedOperationException();
        }

        @Bean
        LockOperations userLocks() {
            throw new UnsupportedOperationException();
        }

        @Bean
        RedisScriptOperations userScripts() {
            throw new UnsupportedOperationException();
        }
    }
}
