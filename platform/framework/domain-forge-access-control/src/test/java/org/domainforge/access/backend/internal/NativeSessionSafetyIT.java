package org.domainforge.access.backend.internal;

import static org.junit.jupiter.api.Assertions.*;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.config.Protocol;

/** 删除 SQL 许可前的原生安全门槛。真实 Redis ACL 注入一次部分撤销失败，不模拟 DAO。 */
class NativeSessionSafetyIT {
  @Test
  void successfulRevocationAfterPartialFailureMustRejectEveryOldToken() throws Exception {
    verifyRevocation(true, false);
  }

  @Test
  void healthyRevocationRejectsEveryOldToken() throws Exception {
    verifyRevocation(false, false);
  }

  @Test
  void tokenFirstNativeApiCanRetryThisSpecificDeletionFailure() throws Exception {
    verifyRevocation(true, true);
  }

  /** 四个原生调用并发写账号会话；不替换 DAO，也不注入框架内部对象。 */
  @Test
  void tokenFirstRevocationMustFindEveryConcurrentlyIssuedToken() throws Exception {
    var config = new Config().setProtocol(Protocol.RESP3).setThreads(2).setNettyThreads(2);
    config
        .useSingleServer()
        .setAddress("redis://127.0.0.1:" + required("REDIS_PORT"))
        .setPassword(required("REDIS_PASSWORD"))
        .setRetryAttempts(0)
        .setTimeout(1000)
        .setConnectionMinimumIdleSize(1)
        .setConnectionPoolSize(4);
    var client = Redisson.create(config);
    var previous = SaManager.getSaTokenDao();
    var previousJson = SaManager.getSaJsonTemplate();
    String type = "concurrent_" + UUID.randomUUID().toString().replace("-", "");
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      SaManager.setSaTokenDao(new SaTokenDaoForRedisson(client));
      SaManager.setSaJsonTemplate(new cn.dev33.satoken.json.SaJsonTemplateForJackson3());
      var logic =
          new StpLogic(type)
              .setConfig(
                  new SaTokenConfig()
                      .setTokenName(type)
                      .setIsShare(false)
                      .setTimeout(60)
                      .setActiveTimeout(-1));
      for (int round = 0; round < 10; round++) {
        String account = "account-" + round;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pending = new ArrayList<java.util.concurrent.Future<String>>();
        for (int index = 0; index < 4; index++) {
          pending.add(
              workers.submit(
                  () -> {
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return logic.createLoginSession(
                        account, new SaLoginParameter().setIsShare(false).setTimeout(60));
                  }));
        }
        start.countDown();
        var tokens = new ArrayList<String>();
        for (var result : pending)
          tokens.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
            4,
            tokens.stream()
                .filter(token -> account.equals(logic.getLoginIdByToken(token)))
                .count());
        var terminals = List.copyOf(logic.getTerminalListByLoginId(account));
        terminals.forEach(terminal -> logic.logoutByTokenValue(terminal.getTokenValue()));
        long remaining =
            tokens.stream().filter(token -> logic.getLoginIdByToken(token) != null).count();
        System.out.printf(
            "NATIVE_CONCURRENT round=%d issued=4 indexed=%d remaining=%d%n",
            round, terminals.size(), remaining);
        assertEquals(0, remaining, "并发签发后逐 token 注销必须找到全部旧会话，否则不能仅据终端索引证明全部撤销");
      }
    } finally {
      SaManager.setSaTokenDao(previous);
      SaManager.setSaJsonTemplate(previousJson);
      try {
        client.getKeys().deleteByPattern(type + ":*");
      } finally {
        client.shutdown();
      }
    }
  }

  private static void revoke(StpLogic logic, boolean tokenFirst) {
    if (tokenFirst) {
      var terminals = List.copyOf(logic.getTerminalListByLoginId("safety-account"));
      terminals.forEach(terminal -> logic.logoutByTokenValue(terminal.getTokenValue()));
    } else {
      logic.logout("safety-account");
    }
  }

  private static void verifyRevocation(boolean partialFailure, boolean tokenFirst)
      throws Exception {
    String user = "verify_" + UUID.randomUUID().toString().replace("-", "");
    String password = UUID.randomUUID().toString();
    redis("ACL", "SETUSER", user, "on", ">" + password, "~*", "&*", "+@all");
    var config = new Config().setProtocol(Protocol.RESP3).setThreads(2).setNettyThreads(2);
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
    var previous = SaManager.getSaTokenDao();
    var previousJson = SaManager.getSaJsonTemplate();
    SaManager.setSaJsonTemplate(new cn.dev33.satoken.json.SaJsonTemplateForJackson3());
    String type = "verification_" + user;
    try {
      var dao = new SaTokenDaoForRedisson(client);
      assertSame(client, dao.redissonClient);
      SaManager.setSaTokenDao(dao);
      var logic =
          new StpLogic(type)
              .setConfig(
                  new SaTokenConfig()
                      .setTokenName(type)
                      .setIsShare(false)
                      .setTimeout(60)
                      .setActiveTimeout(-1));
      String token =
          logic.createLoginSession(
              "safety-account", new SaLoginParameter().setIsShare(false).setTimeout(60));
      assertEquals("safety-account", logic.getLoginIdByToken(token));
      if (partialFailure) {
        redis("ACL", "SETUSER", user, "-del");
        assertThrows(RuntimeException.class, () -> revoke(logic, tokenFirst));
        assertEquals(
            tokenFirst ? 1 : 0,
            logic.getTerminalListByLoginId("safety-account").size(),
            "核对故障点与原生调用顺序");
        assertEquals("safety-account", logic.getLoginIdByToken(token), "故障应保留原生 token 映射");
        redis("ACL", "SETUSER", user, "+del");
      }
      assertDoesNotThrow(() -> revoke(logic, tokenFirst));
      var reader = new StpLogic(type).setConfig(logic.getConfig());
      assertTrue(
          reader.getLoginIdByToken(token) == null, "恢复后的账号撤销返回成功，但旧 token 仍有效；不得据此删除 SQL 安全许可");
    } finally {
      SaManager.setSaTokenDao(previous);
      SaManager.setSaJsonTemplate(previousJson);
      client.shutdown();
      redis("ACL", "DELUSER", user);
    }
  }

  private static void redis(String... arguments) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                "docker",
                "exec",
                "-e",
                "REDISCLI_AUTH",
                required("REDIS_CONTAINER"),
                "redis-cli",
                "--no-auth-warning"));
    command.addAll(List.of(arguments));
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("REDISCLI_AUTH", required("REDIS_PASSWORD"));
    var process = builder.start();
    assertTrue(
        process.waitFor(
            Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, process.exitValue());
    assertTrue(output.equals("OK") || output.equals("1"), "本次隔离 Redis 故障准备未完成");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertNotNull(value, "缺少本次隔离环境 " + name);
    return value;
  }
}
