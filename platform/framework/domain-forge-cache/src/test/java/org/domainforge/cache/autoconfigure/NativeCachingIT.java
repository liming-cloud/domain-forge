package org.domainforge.cache.autoconfigure;

import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.ClientSideCachingOptions;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Protocol;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定原生组合的真实 Redis 证明；只使用本次 deploy 创建的隔离实例。
 */
class NativeCachingIT {
    private static void redis(String... arguments) throws Exception {
        var command =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                "docker",
                                "exec",
                                "-e",
                                "REDISCLI_AUTH",
                                required("REDIS_CONTAINER"),
                                "redis-cli",
                                "--no-auth-warning"));
        command.addAll(java.util.List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("REDISCLI_AUTH", required("REDIS_PASSWORD"));
        var process = builder.start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        String output =
                new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .trim();
        assertEquals(0, process.exitValue());
        assertTrue(output.equals("OK") || output.equals("1"), "隔离 Redis 故障准备未完成");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "原生失效／TTL 未在测试窗口内生效");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "先使用本次 deploy 创建真实 Redis 并提供 " + name);
        return value;
    }

    @Test
    void nativeBootClientCoversOverrideInvalidationExpiryAndOptionalL1() throws Exception {
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
            assertEquals(Protocol.RESP3, client.getConfig().getProtocol());
            assertEquals(0, client.getConfig().useSingleServer().getRetryAttempts());
            assertEquals(16, client.getConfig().useSingleServer().getConnectionPoolSize());
            var writer = Redisson.create(client.getConfig());
            var local =
                    client.getClientSideCaching(
                            ClientSideCachingOptions.defaults()
                                    .evictionPolicy(ClientSideCachingOptions.EvictionPolicy.LRU)
                                    .size(32)
                                    .timeToLive(Duration.ofMillis(500)));
            String key = "verification:cache:" + UUID.randomUUID();
            try {
                var remote = writer.<String>getBucket(key, StringCodec.INSTANCE);
                var cached = local.<String>getBucket(key, StringCodec.INSTANCE);
                remote.set("first", Duration.ofSeconds(5));
                assertEquals("first", cached.get());
                remote.set("second", Duration.ofSeconds(5));
                await(() -> "second".equals(cached.get()));
                assertEquals("second", client.<String>getBucket(key, StringCodec.INSTANCE).get());
                remote.delete();
                await(() -> cached.get() == null);
                remote.set("expires", Duration.ofMillis(250));
                Thread.sleep(180);
                assertEquals("expires", cached.get());
                await(() -> cached.get() == null);
                remote.set("recovered", Duration.ofSeconds(5));
                await(() -> "recovered".equals(cached.get()));
            } finally {
                writer.getBucket(key).delete();
                local.destroy();
                writer.shutdown();
            }
        }
    }

    @Test
    void nativeL1MetadataMustRemainBoundedForAbsentKeys() throws Exception {
        var config =
                new org.redisson.config.Config()
                        .setProtocol(Protocol.RESP3)
                        .setThreads(2)
                        .setNettyThreads(2);
        config
                .useSingleServer()
                .setAddress("redis://127.0.0.1:" + required("REDIS_PORT"))
                .setPassword(required("REDIS_PASSWORD"))
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        var client = Redisson.create(config);
        var local =
                client.getClientSideCaching(
                        ClientSideCachingOptions.defaults()
                                .evictionPolicy(ClientSideCachingOptions.EvictionPolicy.LRU)
                                .size(8)
                                .timeToLive(Duration.ofMillis(100)));
        try {
            String prefix = "verification:absent:" + UUID.randomUUID() + ":";
            for (int index = 0; index < 128; index++) {
                assertNull(local.getBucket(prefix + index, StringCodec.INSTANCE).get());
            }
            Thread.sleep(300);
            // 固定版本原生资源核查；不将反射字段名作为产品结构门禁。
            var field = local.getClass().getDeclaredField("name2cacheKey");
            field.setAccessible(true);
            int retained = ((java.util.Map<?, ?>) field.get(local)).size();
            assertTrue(retained <= 8, "L1 容量 8／TTL 100ms 后附属键记录仍保留 " + retained);
        } finally {
            local.destroy();
            client.shutdown();
        }
    }

    @Test
    void nativeL1ExpiryMustFetchAgainEvenWithoutInvalidation() throws Exception {
        String user = "expiry_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        redis("ACL", "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all");
        var config =
                new org.redisson.config.Config()
                        .setProtocol(Protocol.RESP3)
                        .setThreads(2)
                        .setNettyThreads(2);
        config
                .useSingleServer()
                .setAddress("redis://127.0.0.1:" + required("REDIS_PORT"))
                .setUsername(user)
                .setPassword(password)
                .setRetryAttempts(0)
                .setTimeout(1000)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        var client = Redisson.create(config);
        var local =
                client.getClientSideCaching(
                        ClientSideCachingOptions.defaults()
                                .evictionPolicy(ClientSideCachingOptions.EvictionPolicy.LRU)
                                .size(8)
                                .timeToLive(Duration.ofMillis(100)));
        String key = "verification:expiry:" + UUID.randomUUID();
        try {
            var remote = client.<String>getBucket(key, StringCodec.INSTANCE);
            var cached = local.<String>getBucket(key, StringCodec.INSTANCE);
            remote.set("live-l2", Duration.ofSeconds(60));
            assertEquals("live-l2", cached.get());
            redis("ACL", "SETUSER", user, "-get");
            assertThrows(RuntimeException.class, remote::get, "故障必须确实阻止原生 GET");
            Thread.sleep(400);
            assertThrows(RuntimeException.class, cached::get, "L1 超过自身 TTL 后必须重新读取 Redis；不得无限返回本地旧值");
        } finally {
            redis("ACL", "SETUSER", user, "+get");
            client.getBucket(key).delete();
            local.destroy();
            client.shutdown();
            redis("ACL", "DELUSER", user);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }
}
