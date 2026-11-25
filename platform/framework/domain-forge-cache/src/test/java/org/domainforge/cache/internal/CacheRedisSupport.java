package org.domainforge.cache.internal;

import com.github.benmanes.caffeine.cache.Ticker;
import org.awaitility.Awaitility;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.Protocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只操作本次 deploy 的隔离 Redis；凭据通过环境传给进程，不进入日志。
 */
final class CacheRedisSupport {
    private CacheRedisSupport() {
    }

    static RedissonClient client() {
        return client("default", required("REDIS_PASSWORD"));
    }

    static RedissonClient client(String user, String password) {
        var config = new Config()
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
                .setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }

    static String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String redis(String... arguments) throws Exception {
        var command =
                new ArrayList<>(
                        List.of(
                                "docker",
                                "exec",
                                "-e",
                                "REDISCLI_AUTH",
                                required("REDIS_CONTAINER"),
                                "redis-cli",
                                "--no-auth-warning",
                                "--raw"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("REDISCLI_AUTH", required("REDIS_PASSWORD"));
        var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "隔离 Redis 命令超时");
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, process.exitValue());
            assertFalse(output.startsWith("ERR") || output.startsWith("NOPERM"), "隔离 Redis 准备失败");
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    static void await(BooleanSupplier condition) {
        Awaitility.await("原生通知／重连生效")
                .atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(10))
                .until(condition::getAsBoolean);
    }

    /**
     * 执行 SET 写入并等到 {@code client} 收到该键的 set 通知。L1 的失效监听先于本监听登记，同一通知按登记顺序分发，
     * 因此返回后填充的本地副本不会再被这次写入的通知清掉。
     */
    static void writeNotified(RedissonClient client, String key, Runnable write) throws InterruptedException {
        var topic = client.getPatternTopic(
                "__keyevent@" + client.getConfig().useSingleServer().getDatabase() + "__:*", StringCodec.INSTANCE);
        var notified = new CountDownLatch(1);
        int listener = topic.addListener(String.class, (pattern, channel, name) -> {
            if (key.equals(name) && channel.toString().endsWith(":set")) {
                notified.countDown();
            }
        });
        try {
            write.run();
            assertTrue(notified.await(10, TimeUnit.SECONDS), "未收到 " + key + " 的原生通知");
        } finally {
            topic.removeListener(listener);
        }
    }

    static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "先启动隔离 deploy 并提供 " + name);
        return value;
    }

    /**
     * 可拨动的计时器：注入 L1 后由测试推进时间，验证本地 TTL 不等待真实时间。
     */
    static final class ManualTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
