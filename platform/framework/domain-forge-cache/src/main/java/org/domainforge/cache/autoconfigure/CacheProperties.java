package org.domainforge.cache.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 缓存配置（前缀 {@code domainforge.cache}）。资源参数的默认值只在 cache-defaults.yml，缺失或越界不静默回退。
 *
 * @param threads                       Redisson 工作线程数
 * @param nettyThreads                  Netty 线程数
 * @param connectionPoolSize            连接池大小
 * @param connectionMinimumIdleSize     连接池最小空闲数
 * @param subscriptionPoolSize          订阅连接池大小
 * @param subscriptionMinimumIdleSize   订阅连接最小空闲数
 * @param retryAttempts                 自动重试次数，只能为 0（结果未知的写入不重试）
 * @param regions                       缓存区域，键为区域名
 */
@ConfigurationProperties("domainforge.cache")
public record CacheProperties(
        Integer threads,
        Integer nettyThreads,
        Integer connectionPoolSize,
        Integer connectionMinimumIdleSize,
        Integer subscriptionPoolSize,
        Integer subscriptionMinimumIdleSize,
        Integer retryAttempts,
        Map<String, CacheRegionProperties> regions) {
    /**
     * 未声明区域时为空集合。
     */
    public CacheProperties {
        if (regions == null) regions = Map.of();
    }
}
