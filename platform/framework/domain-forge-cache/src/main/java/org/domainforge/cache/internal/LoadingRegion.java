package org.domainforge.cache.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.domainforge.cache.api.CacheRegion;
import org.domainforge.cache.api.CacheUnavailableException;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 区域的公开行为：在 L1／L2 读写之上提供回源合并、并发上限、可选跨实例锁、空值短期缓存与事务规则。
 *
 * <p>回源在调用线程执行，不新建线程池；合并记录在加载结束或异常时移除。
 */
final class LoadingRegion<T> implements CacheRegion<T> {
    private static final Log LOG = LogFactory.getLog(LoadingRegion.class);
    private final RedissonClient client;
    private final String name;
    private final RedisCacheRegion<T> storage;
    private final RegionSettings settings;
    private final Semaphore loads;
    private final ConcurrentHashMap<String, CompletableFuture<T>> inflight = new ConcurrentHashMap<>();

    LoadingRegion(RedissonClient client, String name, RedisCacheRegion<T> storage, RegionSettings settings) {
        this.client = client;
        this.name = name;
        this.storage = storage;
        this.settings = settings;
        loads = new Semaphore(settings.loadConcurrency());
    }

    @Override
    public Optional<T> get(String key) {
        try {
            return Optional.ofNullable(storage.get(key));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new CacheUnavailableException("缓存暂不可用", failure);
        }
    }

    @Override
    public void put(String key, T value) {
        if (writeTransaction()) throw new IllegalStateException("写事务中不能写入缓存，提交后再写入或使用 evict");
        write(key, value);
    }

    private void write(String key, T value) {
        try {
            storage.put(key, value, ttl());
            if (!settings.nullTtl().isZero()) marker(key).delete();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new CacheUnavailableException("缓存暂不可用", failure);
        }
    }

    @Override
    public void evict(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remove(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    remove(key);
                } catch (RuntimeException failure) {
                    // 已提交的数据库事务不能因缓存删除失败而回滚或重试；旧值在 TTL 内可能被读到。
                    LOG.warn("cache_evict_after_commit_failed region=" + name + " type="
                            + failure.getClass().getSimpleName());
                }
            }
        });
    }

    private void remove(String key) {
        try {
            storage.evict(key);
            if (!settings.nullTtl().isZero()) marker(key).delete();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new CacheUnavailableException("缓存暂不可用", failure);
        }
    }

    @Override
    public T getOrLoad(String key, Supplier<T> loader) {
        Cached<T> cached = cached(key);
        if (cached.hit()) return cached.value();
        if (cached.failed()) return settings.fallbackOnFailure() ? bounded(loader) : throwUnavailable(cached);
        CompletableFuture<T> mine = new CompletableFuture<>();
        CompletableFuture<T> leader = inflight.putIfAbsent(key, mine);
        if (leader != null) return await(leader);
        try {
            T value = bounded(() -> loadOnce(key, loader));
            mine.complete(value);
            return value;
        } catch (RuntimeException failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            inflight.remove(key, mine);
        }
    }

    /**
     * 跨实例锁内先重读 L2，仍未命中才回源并写入。
     */
    private T loadOnce(String key, Supplier<T> loader) {
        if (!settings.crossInstanceLock()) return loadAndStore(key, loader);
        var lock = client.getLock("domainforge:cache-load:" + name + ":" + key);
        boolean locked;
        try {
            locked = lock.tryLock(settings.loadWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CacheUnavailableException("等待回源被中断", interrupted);
        }
        if (!locked) throw new CacheUnavailableException("等待跨实例回源超时", null);
        try {
            Cached<T> again = cached(key);
            return again.hit() ? again.value() : loadAndStore(key, loader);
        } finally {
            lock.unlock();
        }
    }

    private T loadAndStore(String key, Supplier<T> loader) {
        T value = loader.get();
        if (writeTransaction()) return value;
        try {
            if (value != null) storage.put(key, value, ttl());
            else if (!settings.nullTtl().isZero()) marker(key).set("1", settings.nullTtl());
        } catch (RuntimeException failure) {
            LOG.warn("cache_fill_failed region=" + name + " type=" + failure.getClass().getSimpleName());
        }
        return value;
    }

    private T bounded(Supplier<T> load) {
        boolean permitted;
        try {
            permitted = loads.tryAcquire(settings.loadWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CacheUnavailableException("等待回源被中断", interrupted);
        }
        if (!permitted) throw new CacheUnavailableException("回源并发已满", null);
        try {
            return load.get();
        } finally {
            loads.release();
        }
    }

    private T await(CompletableFuture<T> leader) {
        try {
            return leader.get(settings.loadWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CacheUnavailableException("等待回源被中断", interrupted);
        } catch (TimeoutException timeout) {
            throw new CacheUnavailableException("等待合并回源超时", timeout);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new CacheUnavailableException("回源失败", failed.getCause());
        }
    }

    private Cached<T> cached(String key) {
        try {
            T value = storage.get(key);
            if (value != null) return new Cached<>(true, value, null);
            boolean absent = !settings.nullTtl().isZero() && marker(key).isExists();
            return new Cached<>(absent, null, null);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            return new Cached<>(false, null, failure);
        }
    }

    private T throwUnavailable(Cached<T> cached) {
        throw new CacheUnavailableException("缓存暂不可用", cached.failure());
    }

    private org.redisson.api.RBucket<String> marker(String key) {
        return client.getBucket("domainforge:cache-null:" + name + ":" + key, StringCodec.INSTANCE);
    }

    private Duration ttl() {
        Duration ttl = settings.ttl();
        if (!settings.ttlJitter()) return ttl;
        long cut = ThreadLocalRandom.current().nextLong(ttl.toMillis() / 10 + 1);
        return ttl.minusMillis(cut);
    }

    private static boolean writeTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    /**
     * 一次缓存读取的结果：命中、明确不存在（hit 且 value 为 null）、未命中或读取失败。
     */
    private record Cached<T>(boolean hit, T value, RuntimeException failure) {
        boolean failed() {
            return failure != null;
        }
    }
}
