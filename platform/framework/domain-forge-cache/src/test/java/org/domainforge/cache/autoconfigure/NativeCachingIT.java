package org.domainforge.cache.autoconfigure;

import org.awaitility.Awaitility;
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

    private static void await(BooleanSupplier condition) {
        Awaitility.await("原生失效／TTL 生效")
                .atMost(Duration.ofSeconds(3))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(10))
                .until(condition::getAsBoolean);
    }

    // 原生 L1 的 TTL 由 Redisson 内部计时，不能注入时钟：在超过 TTL 的整段窗口内持续断言（T-09 登记）。
    private static void holds(String what, Duration window, BooleanSupplier condition) {
        Awaitility.await(what)
                .during(window)
                .atMost(window.plusMillis(500))
                .pollInterval(Duration.ofMillis(20))
                .until(condition::getAsBoolean);
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

    // 已知缺陷证据：原生 L1 对缺失键的附属记录不受容量约束，这是 L1 改用 Caffeine 的原因之一。
    // 若升级后此断言失败，说明原生实现已修复，应重新评估 L1 选型。
    @Test
    void nativeL1MetadataIsNotBoundedForAbsentKeys() throws Exception {
        var config =
                new org.redisson.config.Config()
                        .setProtocol(Protocol.RESP3)
                        .setThreads(2)
                        .setNettyThreads(2)
                        .setPassword(required("REDIS_PASSWORD"));
        config
                .useSingleServer()
                .setAddress("redis://127.0.0.1:" + required("REDIS_PORT"))
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
            // 固定版本原生资源核查；不将反射字段名作为产品结构门禁。
            var field = local.getClass().getDeclaredField("name2cacheKey");
            field.setAccessible(true);
            var retained = (java.util.Map<?, ?>) field.get(local);
            holds("原生 L1 附属键记录不受容量约束（已受约束时重新评估 L1 选型）", Duration.ofMillis(300), () -> retained.size() > 8);
        } finally {
            local.destroy();
            client.shutdown();
        }
    }

    // 已知缺陷证据：原生 L1 超过自身 TTL 后仍返回本地旧值，不重新读取 Redis。
    // 若升级后此断言失败，说明原生实现已修复，应重新评估 L1 选型。
    @Test
    void nativeL1KeepsServingAfterOwnTtlWithoutInvalidation() throws Exception {
        String user = "expiry_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        redis("ACL", "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all");
        var config =
                new org.redisson.config.Config()
                        .setProtocol(Protocol.RESP3)
                        .setThreads(2)
                        .setNettyThreads(2)
                        .setUsername(user)
                        .setPassword(password);
        config
                .useSingleServer()
                .setAddress("redis://127.0.0.1:" + required("REDIS_PORT"))
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
            // 超过 TTL 后若重新读取 Redis，GET 被拒绝而抛出异常：说明原生实现已修复，重新评估 L1 选型。
            holds("原生 L1 超过 TTL 后仍返回本地旧值", Duration.ofMillis(400), () -> "live-l2".equals(cached.get()));
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
