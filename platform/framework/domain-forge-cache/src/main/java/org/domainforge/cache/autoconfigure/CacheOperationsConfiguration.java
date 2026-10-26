package org.domainforge.cache.autoconfigure;

import org.domainforge.cache.api.CacheOperations;
import org.domainforge.cache.api.LockOperations;
import org.domainforge.cache.api.RedisScriptOperations;
import org.domainforge.cache.internal.RedissonCacheOperations;
import org.domainforge.cache.internal.RedissonLocks;
import org.domainforge.cache.internal.RedissonScripts;
import org.domainforge.cache.internal.RegionSettings;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存、锁与脚本的公开接口装配，复用 Redisson 原生客户端；区域集合在启动时固定。
 */
@AutoConfiguration(afterName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@ConditionalOnBean(RedissonClient.class)
public class CacheOperationsConfiguration {
    /**
     * 按配置创建全部区域；未声明区域时为空集合，调用 region 会被拒绝。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CacheOperations.class)
    public RedissonCacheOperations cacheOperations(RedissonClient client, Environment environment) {
        Map<String, RegionSettings> settings = new LinkedHashMap<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Binder.get(environment)
                .bind("domainforge.cache.regions", Bindable.mapOf(String.class, CacheRegionProperties.class))
                .orElse(Map.of())
                .forEach((name, properties) -> settings.put(name, properties.settings(loader)));
        return new RedissonCacheOperations(client, settings);
    }

    /**
     * 跨实例互斥。
     */
    @Bean
    @ConditionalOnMissingBean(LockOperations.class)
    public LockOperations lockOperations(RedissonClient client) {
        return new RedissonLocks(client);
    }

    /**
     * 已评审 Lua 脚本的执行入口。
     */
    @Bean
    @ConditionalOnMissingBean(RedisScriptOperations.class)
    public RedisScriptOperations redisScriptOperations(RedissonClient client) {
        return new RedissonScripts(client);
    }
}
