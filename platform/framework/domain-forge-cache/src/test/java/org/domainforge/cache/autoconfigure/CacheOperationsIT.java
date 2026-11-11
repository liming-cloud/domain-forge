package org.domainforge.cache.autoconfigure;

import org.domainforge.cache.api.CacheOperations;
import org.domainforge.cache.api.CacheRegion;
import org.domainforge.cache.api.CacheUnavailableException;
import org.domainforge.cache.api.LockOperations;
import org.domainforge.cache.api.LockUnavailableException;
import org.domainforge.cache.api.RedisScript;
import org.domainforge.cache.api.RedisScriptOperations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开缓存、锁与脚本接口在真实 Redis 上的行为。
 */
class CacheOperationsIT {
    private static ConfigurableApplicationContext application;
    private static CacheOperations caches;

    @BeforeAll
    static void start() {
        String port = System.getenv("REDIS_PORT");
        assertTrue(port != null, "需要真实 Redis 验证环境");
        String type = "--domainforge.cache.regions.%s.type=" + Product.class.getName();
        application = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run("--spring.data.redis.port=" + port,
                        "--spring.data.redis.password=" + System.getenv("REDIS_PASSWORD"),
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        type.formatted("products"),
                        "--domainforge.cache.regions.products.ttl=10m",
                        type.formatted("absent"),
                        "--domainforge.cache.regions.absent.ttl=10m",
                        "--domainforge.cache.regions.absent.null-ttl=30s",
                        type.formatted("narrow"),
                        "--domainforge.cache.regions.narrow.ttl=10m",
                        "--domainforge.cache.regions.narrow.load-concurrency=1",
                        "--domainforge.cache.regions.narrow.load-wait=100ms",
                        type.formatted("queued"),
                        "--domainforge.cache.regions.queued.ttl=10m",
                        "--domainforge.cache.regions.queued.load-concurrency=1",
                        "--domainforge.cache.regions.queued.load-wait=300ms");
        caches = application.getBean(CacheOperations.class);
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void putGetEvictWithBoundedTtl() {
        CacheRegion<Product> products = caches.region("products", Product.class);
        String key = key();
        products.put(key, new Product("p1", 12));
        assertEquals(new Product("p1", 12), products.get(key).orElseThrow());
        long ttl = application.getBean(RedissonClient.class)
                .getBucket("domainforge:cache:products:" + key, StringCodec.INSTANCE).remainTimeToLive();
        assertTrue(ttl <= 600_000 && ttl >= 539_000, "TTL 在 10 分钟内向下抖动不超过 10%：" + ttl);
        products.evict(key);
        assertTrue(products.get(key).isEmpty());
    }

    @Test
    void regionsAreFixedByConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> caches.region("products", String.class));
        assertThrows(IllegalArgumentException.class, () -> caches.region("undeclared", Product.class));
        assertThrows(IllegalArgumentException.class, () -> caches.region("products", Product.class).get(" "));
    }

    // 同进程同键的并发回源合并为一次：其余调用方都在等待进行中的回源之后才放行。
    @Test
    void concurrentMissesLoadOnce() throws Exception {
        CacheRegion<Product> products = caches.region("products", Product.class);
        String key = key();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> followers = new CopyOnWriteArrayList<>();
        Callable<Product> call = () -> products.getOrLoad(key, () -> {
            loads.incrementAndGet();
            loading.countDown();
            await(release);
            return new Product("p2", 1);
        });
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Product>> results = new ArrayList<>(List.of(pool.submit(call)));
            assertTrue(loading.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 7; index++) {
                results.add(pool.submit(() -> {
                    followers.add(Thread.currentThread());
                    return call.call();
                }));
            }
            assertTrue(within(Duration.ofSeconds(2),
                    () -> followers.size() == 7 && followers.stream().allMatch(CacheOperationsIT::awaitingLeader)),
                    "其余调用方应在等待进行中的回源");
            release.countDown();
            for (Future<Product> result : results) assertEquals("p2", result.get(2, TimeUnit.SECONDS).name());
        }
        assertEquals(1, loads.get());
        assertEquals("p2", products.getOrLoad(key, () -> null).name());
    }

    // 不存在默认不缓存；配置 null-ttl 的区域短期缓存不存在的事实，写入后立即失效。
    @Test
    void absenceIsCachedOnlyWhenConfigured() {
        AtomicInteger loads = new AtomicInteger();
        CacheRegion<Product> products = caches.region("products", Product.class);
        String key = key();
        products.getOrLoad(key, () -> missing(loads));
        products.getOrLoad(key, () -> missing(loads));
        assertEquals(2, loads.get());
        CacheRegion<Product> absent = caches.region("absent", Product.class);
        assertNull(absent.getOrLoad(key, () -> missing(loads)));
        assertNull(absent.getOrLoad(key, () -> missing(loads)));
        assertEquals(3, loads.get());
        absent.put(key, new Product("created", 1));
        assertEquals("created", absent.getOrLoad(key, () -> missing(loads)).name());
    }

    // 回源并发有上限，超过等待时间明确失败，不排无界队列。
    @Test
    void loadConcurrencyIsBounded() throws Exception {
        CacheRegion<Product> narrow = caches.region("narrow", Product.class);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Product> slow = pool.submit(() -> narrow.getOrLoad(key(), () -> {
                loading.countDown();
                await(release);
                return new Product("slow", 1);
            }));
            assertTrue(loading.await(2, TimeUnit.SECONDS));
            assertThrows(CacheUnavailableException.class,
                    () -> narrow.getOrLoad(key(), () -> new Product("blocked", 1)));
            release.countDown();
            assertEquals("slow", slow.get(2, TimeUnit.SECONDS).name());
        }
    }

    // 写事务里回源的值可能回滚，不交给事务外同键的并发调用方。
    @Test
    void writeTransactionLoadIsNotSharedOutside() throws Exception {
        CacheRegion<Product> products = caches.region("products", Product.class);
        String key = key();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var transactions = new TransactionTemplate(new LocalTransactions());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Product> inside = pool.submit(() -> transactions.execute(status -> products.getOrLoad(key, () -> {
                loading.countDown();
                await(release);
                return new Product("uncommitted", 1);
            })));
            assertTrue(loading.await(2, TimeUnit.SECONDS));
            Future<Product> outside = pool.submit(() -> products.getOrLoad(key, () -> new Product("committed", 1)));
            assertEquals("committed", outside.get(2, TimeUnit.SECONDS).name());
            release.countDown();
            assertEquals("uncommitted", inside.get(2, TimeUnit.SECONDS).name());
        }
        assertEquals("committed", products.get(key).orElseThrow().name());
    }

    // leader 先等许可再回源，合并等待的调用方按 leader 的等待预算等，不提前失败。
    @Test
    void followerOutwaitsLeaderQueuedForPermit() throws Exception {
        CacheRegion<Product> queued = caches.region("queued", Product.class);
        String key = key();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            Future<Product> holder = pool.submit(() -> queued.getOrLoad(key(), () -> {
                holding.countDown();
                await(releaseHolder);
                return new Product("holder", 1);
            }));
            assertTrue(holding.await(2, TimeUnit.SECONDS));
            Callable<Product> call = () -> queued.getOrLoad(key, () -> {
                loading.countDown();
                await(finish);
                return new Product("shared", 1);
            });
            Future<Product> first = pool.submit(call);
            Future<Product> second = pool.submit(call);
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS), "许可被占，尚未回源");
            releaseHolder.countDown();
            assertTrue(loading.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS), "回源进行中");
            finish.countDown();
            assertEquals("shared", first.get(2, TimeUnit.SECONDS).name());
            assertEquals("shared", second.get(2, TimeUnit.SECONDS).name());
            assertEquals("holder", holder.get(2, TimeUnit.SECONDS).name());
        }
    }

    // 写事务中不能写入缓存；evict 延后到提交，回滚则不删除。
    @Test
    void transactionsDeferEvictionAndRejectWrites() {
        CacheRegion<Product> products = caches.region("products", Product.class);
        var transactions = new TransactionTemplate(new LocalTransactions());
        String kept = key();
        String removed = key();
        products.put(kept, new Product("kept", 1));
        products.put(removed, new Product("removed", 1));
        transactions.executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> products.put(key(), new Product("x", 1)));
            products.evict(kept);
            assertTrue(products.get(kept).isPresent(), "提交前不删除");
            status.setRollbackOnly();
        });
        assertTrue(products.get(kept).isPresent(), "回滚不删除");
        transactions.executeWithoutResult(status -> products.evict(removed));
        assertTrue(products.get(removed).isEmpty());
        var readOnly = new TransactionTemplate(new LocalTransactions());
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> products.put(key(), new Product("read-only", 1)));
    }

    @Test
    void locksAreExclusiveAcrossCallers() throws Exception {
        LockOperations locks = application.getBean(LockOperations.class);
        String name = "test:" + key();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> holder = pool.submit(() -> locks.withLock(name, Duration.ZERO, () -> {
                held.countDown();
                await(release);
            }));
            assertTrue(held.await(2, TimeUnit.SECONDS));
            assertThrows(LockUnavailableException.class,
                    () -> locks.withLock(name, Duration.ofMillis(100), () -> "never"));
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
        }
        assertEquals("after", locks.withLock(name, Duration.ofMillis(100), () -> "after"));
        assertThrows(IllegalArgumentException.class, () -> locks.withLock("bad name", Duration.ZERO, () -> 1));
    }

    @Test
    void scriptsStayWithinDeclaredKeys() {
        RedisScriptOperations scripts = application.getBean(RedisScriptOperations.class);
        var increment = new RedisScript<>("scripts/increment.lua", Long.class, "test:counter:");
        String key = "test:counter:" + key();
        assertEquals(2L, scripts.execute(increment, List.of(key), List.of("2")));
        assertEquals(5L, scripts.execute(increment, List.of(key), List.of("3")));
        assertThrows(IllegalArgumentException.class,
                () -> scripts.execute(increment, List.of("other:" + key()), List.of("1")));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisScript<>("scripts/increment.lua", Long.class, "satoken:login:"));
        assertThrows(IllegalArgumentException.class,
                () -> scripts.execute(new RedisScript<>("scripts/missing.lua", Long.class, "test:x:"),
                        List.of("test:x:1"), List.of()));
    }

    private static Product missing(AtomicInteger loads) {
        loads.incrementAndGet();
        return null;
    }

    // 线程正在缓存内部等待同键的进行中回源（合并），而不是自己回源。
    private static boolean awaitingLeader(Thread thread) {
        return Arrays.stream(thread.getStackTrace())
                .anyMatch(frame -> frame.getClassName().equals("org.domainforge.cache.internal.LoadingRegion")
                        && frame.getMethodName().equals("await"));
    }

    private static boolean within(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return true;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 测试用值类型：以 JSON 往返。
     */
    public record Product(String name, int stock) {
    }

    /**
     * 不连接数据库的本地事务管理器，只驱动 Spring 事务同步。
     */
    static final class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }
}
