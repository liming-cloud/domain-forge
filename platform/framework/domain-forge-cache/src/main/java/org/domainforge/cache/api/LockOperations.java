package org.domainforge.cache.api;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 跨实例互斥：基于 Redis 的可重入锁，持有期间看门狗自动续期，结束时由持有线程释放。
 *
 * <p>锁只减少并发冲突，不能替代数据库唯一约束、行锁或版本条件，也不提供分布式事务。
 * 锁在 Redis 故障或进程停顿时可能失效，业务写入仍须由数据库条件兜底。
 */
public interface LockOperations {
    /**
     * 在有限等待内取得锁并执行；等不到锁或 Redis 故障抛出 {@link LockUnavailableException}。
     *
     * @param name 锁名，1 至 128 个字母、数字或 {@code :_-}
     * @param wait 最长等待，0 至 30 秒
     */
    <T> T withLock(String name, Duration wait, Supplier<T> action);

    /**
     * 无返回值的版本。
     */
    default void withLock(String name, Duration wait, Runnable action) {
        withLock(name, wait, () -> {
            action.run();
            return null;
        });
    }
}
