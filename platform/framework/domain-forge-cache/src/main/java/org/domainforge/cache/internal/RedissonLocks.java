package org.domainforge.cache.internal;

import org.domainforge.cache.api.LockOperations;
import org.domainforge.cache.api.LockUnavailableException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 原生 RLock：有限等待、看门狗续期、finally 中由持有线程释放。
 */
public final class RedissonLocks implements LockOperations {
    private final RedissonClient client;

    /**
     * 复用共享客户端，不创建第二套连接。
     */
    public RedissonLocks(RedissonClient client) {
        this.client = client;
    }

    @Override
    public <T> T withLock(String name, Duration wait, Supplier<T> action) {
        if (name == null || !name.matches("[a-zA-Z0-9:_-]{1,128}")) throw new IllegalArgumentException("锁名不合法");
        if (wait == null || wait.isNegative() || wait.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("锁等待须为0至30秒");
        RLock lock = client.getLock("domainforge:lock:" + name);
        acquire(lock, wait);
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private static void acquire(RLock lock, Duration wait) {
        boolean locked;
        try {
            locked = lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LockUnavailableException("等待锁被中断", interrupted);
        } catch (RuntimeException failure) {
            throw new LockUnavailableException("锁服务暂不可用", failure);
        }
        if (!locked) throw new LockUnavailableException("等待锁超时", null);
    }
}
