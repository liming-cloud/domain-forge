package org.domainforge.cache.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.Objects;

/**
 * C1 内部适配：固定类型的普通查询 region；不承担认证或数据库一致性。
 */
final class RedisCacheRegion<T> {
    private final RedissonClient client;
    private final Definition<T> definition;
    private final String prefix;
    private final Cache<String, T> local;
    private volatile boolean closed;
    RedisCacheRegion(RedissonClient client, Definition<T> definition) {
        this.client = client;
        this.definition = definition;
        prefix = "domainforge:cache:" + definition.name() + ":";
        local =
                definition.capacity() == 0
                        ? null
                        : Caffeine.newBuilder()
                        .maximumSize(definition.capacity())
                        .expireAfterWrite(definition.localTtl())
                        .build();
    }

    /**
     * 未命中不建附属键索引、不缓存 null；加载异常原样交给调用者。
     */
    T get(String key) {
        String name = name(key);
        return local == null ? read(name) : local.get(name, this::read);
    }

    /**
     * 有限真实 L2 TTL；无论写入结果是否确定，都删除当前实例的旧副本。
     */
    void put(String key, T value, Duration ttl) {
        String name = name(key);
        Objects.requireNonNull(value);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("L2 TTL 必须为正数");
        }
        try {
            bucket(name).set(definition.type().cast(value), ttl);
        } finally {
            invalidate(name);
        }
    }

    /**
     * 删除结果未知时也不继续返回当前实例旧副本。
     */
    void evict(String key) {
        String name = name(key);
        try {
            bucket(name).delete();
        } finally {
            invalidate(name);
        }
    }

    private T read(String name) {
        return definition.type().cast(bucket(name).get());
    }

    private RBucket<T> bucket(String name) {
        return client.getBucket(name, definition.codec());
    }

    private String name(String key) {
        if (closed) {
            throw new IllegalStateException("缓存 region 已关闭");
        }
        if (key == null || key.isBlank() || key.length() > 512) {
            throw new IllegalArgumentException("缓存键不能为空且不得超过 512 字符");
        }
        return prefix + key;
    }

    boolean localEnabled() {
        return local != null;
    }

    Class<T> type() {
        return definition.type();
    }

    void invalidate(String key) {
        if (local != null && key.startsWith(prefix)) {
            local.invalidate(key);
        }
    }

    void clear() {
        if (local != null) {
            local.invalidateAll();
        }
    }

    void close() {
        closed = true;
        clear();
    }

    /**
     * 原生维护后观测容量，不增加第二套键记录。
     */
    long localSize() {
        if (local == null) {
            return 0;
        }
        local.cleanUp();
        return local.estimatedSize();
    }

    /**
     * 启动时固定值类型；容量为零只读写 L2。
     */
    record Definition<T>(String name, Class<T> type, Codec codec, long capacity, Duration localTtl) {
        Definition {
            if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("缓存 region 名称不合法");
            }
            Objects.requireNonNull(type);
            Objects.requireNonNull(codec);
            if (capacity < 0
                    || capacity > 1_000_000
                    || localTtl == null
                    || localTtl.isNegative()
                    || localTtl.isZero()
                    || localTtl.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("L1 容量或 TTL 超出有限支持范围");
            }
        }
    }
}
