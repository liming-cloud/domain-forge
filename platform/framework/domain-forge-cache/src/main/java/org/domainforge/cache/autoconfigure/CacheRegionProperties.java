package org.domainforge.cache.autoconfigure;

import org.domainforge.cache.internal.RegionSettings;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.ClassUtils;

import java.time.Duration;

/**
 * {@code domainforge.cache.regions.<name>} 的绑定形状；未写的项使用这里的默认值。
 */
public record CacheRegionProperties(
        String type,
        Duration ttl,
        @DefaultValue("true") boolean ttlJitter,
        @DefaultValue("0s") Duration nullTtl,
        @DefaultValue("0") long localCapacity,
        @DefaultValue("30s") Duration localTtl,
        @DefaultValue("16") int loadConcurrency,
        @DefaultValue("1s") Duration loadWait,
        @DefaultValue("false") boolean crossInstanceLock,
        @DefaultValue("false") boolean fallbackOnFailure) {
    /**
     * 解析值类型并校验范围；类型不存在或参数越界时启动失败。
     */
    RegionSettings settings(ClassLoader loader) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("缓存区域须声明 type");
        Class<?> resolved;
        try {
            resolved = ClassUtils.forName(type, loader);
        } catch (ClassNotFoundException | LinkageError missing) {
            throw new IllegalArgumentException("缓存区域的 type 不存在：" + type, missing);
        }
        return new RegionSettings(resolved, ttl, ttlJitter, nullTtl, localCapacity, localTtl, loadConcurrency,
                loadWait, crossInstanceLock, fallbackOnFailure);
    }
}
