package org.domainforge.cache.internal;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNode;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.domainforge.cache.internal.CacheRedisSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * C1 对应原失败断言及真实原生通知；不替代账号安全或完整 M4 的验证。
 */
class LocalCachingIT {
    private static RedisCacheRegion.Definition<String> definition(
            String name, long capacity, Duration ttl) {
        return new RedisCacheRegion.Definition<>(
                name, String.class, StringCodec.INSTANCE, capacity, ttl);
    }

    private static long gets(RedissonClient client) {
        String stat =
                client
                        .getRedisNodes(RedisNodes.SINGLE)
                        .getInstance()
                        .info(RedisNode.InfoSection.COMMANDSTATS)
                        .getOrDefault("cmdstat_get", "calls=0,");
        return Long.parseLong(stat.substring("calls=".length(), stat.indexOf(',')));
    }

    private static StringCodec delayedDecoder(
            java.util.concurrent.CountDownLatch decoded, java.util.concurrent.CountDownLatch release) {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        return new StringCodec() {
            @Override
            public org.redisson.client.protocol.Decoder<Object> getValueDecoder() {
                return (buffer, state) -> {
                    Object value = super.getValueDecoder().decode(buffer, state);
                    if (first.compareAndSet(true, false)) {
                        decoded.countDown();
                        try {
                            if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new java.io.IOException("测试未释放已读结果");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(interrupted);
                        }
                    }
                    return value;
                };
            }
        };
    }

    private static long[] invalidationSamples(
            RedisCacheRegion<String> cache, RedisCacheRegion<String> remote) throws Exception {
        var samples = new long[30];
        for (int i = 0; i < samples.length; i++) {
            long started = System.nanoTime();
            remote.put("key", "updated-" + i, Duration.ofSeconds(60));
            await(() -> cache.localSize() == 0);
            assertEquals("updated-" + i, cache.get("key"));
            samples[i] = System.nanoTime() - started;
        }
        java.util.Arrays.sort(samples);
        return samples;
    }

    @Test
    void absentAndPresentRandomKeysRemainBounded() throws Exception {
        var client = client();
        String name = unique();
        try (var caches =
                     new RedisCacheRegions(client, List.of(definition(name, 8, Duration.ofMillis(100))))) {
            var cache = caches.region(name, String.class);
            for (int index = 0; index < 512; index++) {
                assertNull(cache.get("absent-" + index));
            }
            assertEquals(0, cache.localSize());
            for (int index = 0; index < 128; index++) {
                cache.put("present-" + index, "value", Duration.ofSeconds(5));
                assertEquals("value", cache.get("present-" + index));
            }
            assertTrue(cache.localSize() <= 8);
            Thread.sleep(300);
            assertEquals(0, cache.localSize(), "原有容量和 TTL 断言同时保留");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void localExpiryForcesRealGetWithoutNotification() throws Exception {
        String user = "expiry_" + unique();
        String password = unique();
        redis("ACL", "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all");
        var client = client(user, password);
        String name = unique();
        try (var caches =
                     new RedisCacheRegions(client, List.of(definition(name, 8, Duration.ofMillis(100))))) {
            var cache = caches.region(name, String.class);
            cache.put("key", "live-l2", Duration.ofSeconds(60));
            Thread.sleep(100);
            assertEquals("live-l2", cache.get("key"));
            redis("ACL", "SETUSER", user, "-get");
            assertThrows(
                    RuntimeException.class,
                    () -> client.getBucket("domainforge:cache:" + name + ":key").get());
            Thread.sleep(400);
            assertThrows(RuntimeException.class, () -> cache.get("key"), "L1 不能无限返回本地旧值");
        } finally {
            client.shutdown();
            redis("ACL", "DELUSER", user);
        }
    }

    @Test
    void nativeBootClientSupportsMixedSwitchAndCrossInstanceInvalidation() throws Exception {
        try (var context =
                     new SpringApplicationBuilder(Application.class)
                             .web(WebApplicationType.NONE)
                             .run(
                                     "--spring.data.redis.port=" + required("REDIS_PORT"),
                                     "--spring.data.redis.password=" + required("REDIS_PASSWORD"),
                                     "--spring.main.banner-mode=off",
                                     "--logging.level.root=WARN")) {
            assertEquals(1, context.getBeansOfType(RedissonClient.class).size());
            var client = context.getBean(RedissonClient.class);
            var writer = client();
            String name = unique();
            try (var on =
                         new RedisCacheRegions(client, List.of(definition(name, 8, Duration.ofSeconds(30))));
                 var off =
                         new RedisCacheRegions(writer, List.of(definition(name, 0, Duration.ofSeconds(30))))) {
                var cached = on.region(name, String.class);
                var remote = off.region(name, String.class);
                remote.put("key", "first", Duration.ofSeconds(60));
                Thread.sleep(100);
                long before = gets(client);
                for (int i = 0; i < 100; i++) {
                    assertEquals("first", cached.get("key"));
                }
                assertEquals(1, gets(client) - before, "命中必须减少真实 Redis GET");
                before = gets(client);
                for (int i = 0; i < 3; i++) {
                    assertEquals("first", remote.get("key"));
                }
                assertEquals(3, gets(client) - before);
                remote.put("key", "second", Duration.ofSeconds(60));
                await(() -> cached.localSize() == 0);
                assertEquals("second", cached.get("key"));
                remote.evict("key");
                await(() -> cached.localSize() == 0);
                assertNull(cached.get("key"));
                assertEquals(0, remote.localSize());
            } finally {
                writer.shutdown();
            }
            assertTrue(client.getRedisNodes(RedisNodes.SINGLE).getInstance().ping(), "关闭缓存不得关闭共享客户端");
        }
    }

    @Test
    void nearExpiryFillAndConcurrentReadsStayWithinLocalLifetime() throws Exception {
        var client = client();
        String name = unique();
        try (var caches =
                     new RedisCacheRegions(client, List.of(definition(name, 8, Duration.ofMillis(500))))) {
            var cache = caches.region(name, String.class);
            cache.put("key", "near-expiry", Duration.ofMillis(700));
            Thread.sleep(450);
            assertEquals("near-expiry", cache.get("key"));
            await(() -> cache.get("key") == null);
            cache.put("key", "parallel", Duration.ofSeconds(10));
            Thread.sleep(100);
            long before = gets(client);
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                var calls = new ArrayList<Callable<String>>();
                for (int i = 0; i < 64; i++) {
                    calls.add(() -> cache.get("key"));
                }
                for (var result : threads.invokeAll(calls)) {
                    assertEquals("parallel", result.get());
                }
            }
            assertEquals(1, gets(client) - before, "Caffeine 合并同一键的 L2 填充");
            assertTrue(cache.localSize() <= 8);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void reconnectClearsValuesWhoseNotificationsWereLost() throws Exception {
        String user = "reconnect_" + unique();
        String password = unique();
        redis("ACL", "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all");
        var client = client(user, password);
        var writer = client();
        String name = unique();
        try (var caches =
                     new RedisCacheRegions(client, List.of(definition(name, 8, Duration.ofSeconds(30))))) {
            var cache = caches.region(name, String.class);
            cache.put("key", "old", Duration.ofSeconds(60));
            Thread.sleep(100);
            assertEquals("old", cache.get("key"));
            redis("ACL", "SETUSER", user, "off");
            assertTrue(Integer.parseInt(redis("CLIENT", "KILL", "USER", user)) >= 2);
            writer
                    .getBucket("domainforge:cache:" + name + ":key", StringCodec.INSTANCE)
                    .set("new", Duration.ofSeconds(60));
            // 断连期间只允许普通查询在原有限 TTL 内短暂陈旧，不用它授权。
            assertEquals("old", cache.get("key"));
            redis("ACL", "SETUSER", user, "on");
            await(() -> cache.localSize() == 0);
            assertEquals("new", cache.get("key"), "必须在 30s TTL 之前因重订阅清理旧值");
        } finally {
            client.shutdown();
            writer.shutdown();
            redis("ACL", "DELUSER", user);
        }
    }

    @Test
    void missingNotificationsRejectL1ButNotL2Only() throws Exception {
        var client = client();
        var node = client.getRedisNodes(RedisNodes.SINGLE).getInstance();
        String previous = node.getConfig("notify-keyspace-events").get("notify-keyspace-events");
        String name = unique();
        try {
            redis("CONFIG", "SET", "notify-keyspace-events", "");
            assertThrows(
                    IllegalStateException.class,
                    () -> new RedisCacheRegions(client, List.of(definition(name, 8, Duration.ofSeconds(1)))));
            try (var caches =
                         new RedisCacheRegions(client, List.of(definition(name, 0, Duration.ofSeconds(1))))) {
                var cache = caches.region(name, String.class);
                cache.put("key", "value", Duration.ofSeconds(5));
                assertEquals("value", cache.get("key"));
            }
        } finally {
            redis("CONFIG", "SET", "notify-keyspace-events", previous);
            client.shutdown();
        }
    }

    @Test
    void deniedPrerequisiteInspectionRejectsL1() throws Exception {
        String user = "inspection_" + unique();
        String password = unique();
        redis("ACL", "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all", "-config");
        var client = client(user, password);
        try {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            new RedisCacheRegions(
                                    client, List.of(definition(unique(), 8, Duration.ofSeconds(1)))));
        } finally {
            client.shutdown();
            redis("ACL", "DELUSER", user);
        }
    }

    @Test
    void fixedRegionsRejectTypeConfusionAndReleaseOnlyOwnedListeners() throws Exception {
        var client = client();
        String name = unique();
        var string = definition(name, 8, Duration.ofSeconds(30));
        var number =
                new RedisCacheRegion.Definition<>(
                        name + "n", Long.class, LongCodec.INSTANCE, 8, Duration.ofSeconds(30));
        try (var first = new RedisCacheRegions(client, List.of(string, number))) {
            assertThrows(IllegalArgumentException.class, () -> first.region(name, Long.class));
            assertThrows(IllegalArgumentException.class, () -> first.region("unknown", String.class));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RedisCacheRegions(client, List.of(string, string)));
            first.region(name, String.class).put("same-key", "text", Duration.ofSeconds(60));
            first.region(name + "n", Long.class).put("same-key", 42L, Duration.ofSeconds(60));
            assertEquals("text", first.region(name, String.class).get("same-key"));
            assertEquals(42L, first.region(name + "n", Long.class).get("same-key"));
            var second = new RedisCacheRegions(client, List.of(string));
            second.close();
            var closed = second.region(name, String.class);
            assertThrows(IllegalStateException.class, () -> closed.get("same-key"));
            Thread.sleep(100);
            assertEquals("text", first.region(name, String.class).get("same-key"));
            client.getBucket("domainforge:cache:" + name + ":same-key").delete();
            await(() -> first.region(name, String.class).localSize() == 0);
            assertNull(first.region(name, String.class).get("same-key"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void writeDuringInFlightFillCannotExtendLocalExpiry() throws Exception {
        var client = client();
        var writer = client();
        var decoded = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var codec = delayedDecoder(decoded, release);
        String name = unique();
        var definition =
                new RedisCacheRegion.Definition<>(name, String.class, codec, 8, Duration.ofMillis(100));
        try (var caches = new RedisCacheRegions(client, List.of(definition));
             var thread = Executors.newVirtualThreadPerTaskExecutor()) {
            var cache = caches.region(name, String.class);
            cache.put("key", "old", Duration.ofSeconds(60));
            Thread.sleep(100);
            var inflight = thread.submit(() -> cache.get("key"));
            try {
                assertTrue(decoded.await(3, java.util.concurrent.TimeUnit.SECONDS));
                writer
                        .getBucket("domainforge:cache:" + name + ":key", StringCodec.INSTANCE)
                        .set("new", Duration.ofSeconds(60));
            } finally {
                release.countDown();
            }
            assertEquals("old", inflight.get(), "已开始的旧读取可按合同返回，不宣称强一致");
            Thread.sleep(400);
            assertEquals("new", cache.get("key"), "晚填充也必须受自身 TTL 约束");
        } finally {
            release.countDown();
            client.shutdown();
            writer.shutdown();
        }
    }

    @Test
    void recordRealReadAndInvalidationCosts() throws Exception {
        var client = client();
        var writer = client();
        String name = unique();
        try (var on =
                     new RedisCacheRegions(client, List.of(definition(name, 8, Duration.ofSeconds(30))));
             var off =
                     new RedisCacheRegions(writer, List.of(definition(name, 0, Duration.ofSeconds(30))))) {
            var cache = on.region(name, String.class);
            var remote = off.region(name, String.class);
            remote.put("key", "initial", Duration.ofSeconds(60));
            Thread.sleep(100);
            assertEquals("initial", cache.get("key"));
            long before = gets(client);
            long started = System.nanoTime();
            for (int i = 0; i < 500; i++) {
                assertEquals("initial", cache.get("key"));
            }
            long localNanos = System.nanoTime() - started;
            assertEquals(0, gets(client) - before);
            before = gets(client);
            started = System.nanoTime();
            for (int i = 0; i < 500; i++) {
                assertEquals("initial", remote.get("key"));
            }
            long remoteNanos = System.nanoTime() - started;
            assertEquals(500, gets(client) - before);
            long[] samples = invalidationSamples(cache, remote);
            System.out.printf(
                    "C1_MEASURE reads=500 l1_ns=%d l2_ns=%d invalidation_p50_ns=%d invalidation_max_ns=%d%n",
                    localNanos, remoteNanos, samples[15], samples[29]);
        } finally {
            client.shutdown();
            writer.shutdown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }
}
