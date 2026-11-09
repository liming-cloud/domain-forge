package org.domainforge.cache.autoconfigure;

import org.redisson.config.Protocol;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 复用 Redisson 原生资源装配，地址和凭据只来自 spring.data.redis。
 */
@AutoConfiguration(beforeName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {
    /**
     * 默认值只在 YAML，配置缺失或非法不静默回退。
     */
    private static int number(Integer value, String key, int minimum) {
        if (value == null || value < minimum || value > 256) {
            throw new IllegalArgumentException("缓存资源参数超出支持范围：" + key);
        }
        return value;
    }

    /**
     * 固定 RESP3 与有限资源；不自动重试结果未知的写入。
     */
    @Bean
    RedissonAutoConfigurationCustomizer domainForgeRedisson(CacheProperties properties) {
        return config -> {
            config.setProtocol(Protocol.RESP3);
            config.setThreads(number(properties.threads(), "threads", 1));
            config.setNettyThreads(number(properties.nettyThreads(), "netty-threads", 1));
            var server = config.useSingleServer();
            server.setConnectionPoolSize(number(properties.connectionPoolSize(), "connection-pool-size", 1));
            server.setConnectionMinimumIdleSize(
                    number(properties.connectionMinimumIdleSize(), "connection-minimum-idle-size", 1));
            server.setSubscriptionConnectionPoolSize(
                    number(properties.subscriptionPoolSize(), "subscription-pool-size", 1));
            server.setSubscriptionConnectionMinimumIdleSize(
                    number(properties.subscriptionMinimumIdleSize(), "subscription-minimum-idle-size", 1));
            int attempts = number(properties.retryAttempts(), "retry-attempts", 0);
            if (attempts != 0) {
                throw new IllegalArgumentException("共享认证资源不允许自动重试未知结果的写入");
            }
            server.setRetryAttempts(attempts);
        };
    }
}
