package org.domainforge.cache.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.Protocol;

/** 只操作本次 deploy 的隔离 Redis；凭据通过环境传给进程，不进入日志。 */
final class CacheRedisSupport {
  private CacheRedisSupport() {}

  static RedissonClient client() {
    return client("default", required("REDIS_PASSWORD"));
  }

  static RedissonClient client(String user, String password) {
    var config = new Config().setProtocol(Protocol.RESP3).setThreads(2).setNettyThreads(2);
    config
        .useSingleServer()
        .setAddress("redis://127.0.0.1:" + required("REDIS_PORT"))
        .setUsername(user)
        .setPassword(password)
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

  static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "原生通知／重连未在验证窗口内生效");
  }

  static String required(String name) {
    String value = System.getenv(name);
    assertNotNull(value, "先启动隔离 deploy 并提供 " + name);
    return value;
  }
}
