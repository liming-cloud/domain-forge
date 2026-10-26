package org.domainforge.cache.api;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 一个类型固定的缓存区域；键为完整业务键，值以 JSON 保存在 Redis（L2），可选进程内 L1。
 *
 * <p>缓存是普通查询的加速，不是业务事实来源；需要严格当前事实的读取应绕过缓存。
 *
 * @param <T> 区域的值类型，启动时由配置固定
 */
public interface CacheRegion<T> {
    /**
     * 只读缓存，不回源；未命中返回空。Redis 故障抛出 {@link CacheUnavailableException}。
     */
    Optional<T> get(String key);

    /**
     * 写入缓存，TTL 来自区域配置（默认向下 10% 抖动）。
     *
     * <p>在未提交的写事务中调用会被拒绝，避免把未提交数据放进缓存。
     */
    void put(String key, T value);

    /**
     * 删除缓存；在事务中调用时延后到提交之后执行，回滚则不删除。
     */
    void evict(String key);

    /**
     * 读缓存，未命中时回源并写入：同进程同键合并为一次加载，回源并发与等待有上限，可选跨实例锁。
     *
     * <p>loader 返回 null 表示明确不存在：默认不缓存，区域配置了 null-ttl 时短暂缓存不存在的事实。
     * loader 必须没有副作用；其异常原样抛出且不会被缓存。写事务中只加载、不写入缓存。
     *
     * @return 加载或缓存中的值，不存在时为 null
     */
    T getOrLoad(String key, Supplier<T> loader);
}
