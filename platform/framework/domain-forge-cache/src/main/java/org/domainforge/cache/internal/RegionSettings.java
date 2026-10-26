package org.domainforge.cache.internal;

import java.time.Duration;
import java.util.Objects;

/**
 * 一个区域的固定配置；默认值见 cache-defaults.yml，越界在启动时拒绝。
 *
 * @param type              值类型
 * @param ttl               L2 TTL，1 秒至 7 天
 * @param ttlJitter         是否在 TTL 内向下随机 10%，避免同时过期
 * @param nullTtl           明确不存在时的短期缓存，0 为不缓存，至多 5 分钟
 * @param localCapacity     L1 容量，0 为关闭
 * @param localTtl          L1 TTL，至多 1 天
 * @param loadConcurrency   每实例本区域同时回源的上限
 * @param loadWait          等待回源许可、合并结果与跨实例锁的上限
 * @param crossInstanceLock 是否用跨实例锁合并回源
 * @param fallbackOnFailure Redis 故障时 getOrLoad 是否直接回源（仅限可丢弃的缓存）
 */
public record RegionSettings(
        Class<?> type,
        Duration ttl,
        boolean ttlJitter,
        Duration nullTtl,
        long localCapacity,
        Duration localTtl,
        int loadConcurrency,
        Duration loadWait,
        boolean crossInstanceLock,
        boolean fallbackOnFailure) {
    /**
     * 拒绝越界或缺失的配置。
     */
    public RegionSettings {
        Objects.requireNonNull(type, "缓存区域须声明 type");
        within(ttl, Duration.ofSeconds(1), Duration.ofDays(7), "ttl");
        within(nullTtl, Duration.ZERO, Duration.ofMinutes(5), "null-ttl");
        within(loadWait, Duration.ofMillis(1), Duration.ofSeconds(30), "load-wait");
        if (loadConcurrency < 1 || loadConcurrency > 256)
            throw new IllegalArgumentException("load-concurrency 须为1至256");
    }

    private static void within(Duration value, Duration min, Duration max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0)
            throw new IllegalArgumentException("缓存区域参数超出范围：" + name);
    }
}
