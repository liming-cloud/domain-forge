package org.domainforge.cache.internal;

import org.domainforge.cache.api.CacheOperations;
import org.domainforge.cache.api.CacheRegion;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJackson3Codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按配置一次性创建固定区域集合；值以区域声明类型的 JSON 保存，不写入多态类型信息。
 */
public final class RedissonCacheOperations implements CacheOperations, AutoCloseable {
    private final RedisCacheRegions storage;
    private final Map<String, LoadingRegion<?>> regions = new LinkedHashMap<>();
    private final Map<String, Class<?>> types = new LinkedHashMap<>();

    /**
     * 声明了 L1 的区域会在此核对 Redis 通知前提，不满足时启动失败。
     */
    public RedissonCacheOperations(RedissonClient client, Map<String, RegionSettings> settings) {
        var definitions = new ArrayList<RedisCacheRegion.Definition<?>>();
        settings.forEach((name, region) -> definitions.add(definition(name, region)));
        storage = new RedisCacheRegions(client, definitions);
        settings.forEach((name, region) -> {
            regions.put(name, loading(client, name, region));
            types.put(name, region.type());
        });
    }

    private static <T> RedisCacheRegion.Definition<T> definition(String name, RegionSettings settings) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) settings.type();
        return new RedisCacheRegion.Definition<>(name, type, new TypedJsonJackson3Codec(type),
                settings.localCapacity(), settings.localTtl());
    }

    private <T> LoadingRegion<T> loading(RedissonClient client, String name, RegionSettings settings) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) settings.type();
        return new LoadingRegion<>(client, name, storage.region(name, type), settings);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CacheRegion<T> region(String name, Class<T> type) {
        if (types.get(name) != type) throw new IllegalArgumentException("缓存区域未声明或值类型与配置不符：" + name);
        return (CacheRegion<T>) regions.get(name);
    }

    @Override
    public void close() {
        storage.close();
    }
}
