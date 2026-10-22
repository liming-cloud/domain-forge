package org.domainforge.access.backend.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** 实证已批准的候选调用顺序；不把测试探针视为 IAM 已完成的业务实现。 */
class SerializedSessionSafetyIT {
  private final List<SessionProbeProcess> workers = new ArrayList<>();
  private final List<String> users = new ArrayList<>();
  private String schema;
  private String type;
  private SqlSessionFactory database;

  @BeforeEach
  void open() throws Exception {
    schema = "probe_" + UUID.randomUUID().toString().replace("-", "");
    type = schema;
    NativeSessionProbe.schema(schema, "create");
    database = NativeSessionProbe.database(schema, schema + "_observer");
  }

  @AfterEach
  void close() throws Exception {
    var cleanup = new ArrayList<Executable>();
    for (var worker : workers) cleanup.add(worker::close);
    for (String user : users) cleanup.add(() -> redis("ACL", "DELUSER", user));
    cleanup.add(
        () ->
            assertAll(
                Arrays.stream(redis("--scan", "--pattern", type + ":*").split("\\R"))
                    .filter(key -> !key.isBlank())
                    .map(key -> () -> redis("DEL", key))));
    cleanup.add(
        () -> assertTrue(redis("--scan", "--pattern", type + ":*").isBlank(), "只清理本次前缀且确认无残留"));
    cleanup.add(() -> NativeSessionProbe.schema(schema, "drop"));
    assertAll("每项隔离资源都须尝试清理，失败不能跳过其余资源", cleanup);
  }

  @Test
  void twoProcessesSerializeIssuanceAndRevokeEveryIndexedToken() throws Exception {
    var first = worker("a", port(), Map.of());
    var second = worker("b", port(), Map.of());
    first.send("WAIT_LOGIN");
    assertEquals("LOCKED", first.next());
    second.send("LOGIN");
    awaitBlocked(schema + "_b");
    first.send("GO");
    String firstResult = first.next();
    String secondResult = second.next();
    assertTrue(firstResult.startsWith("RESULT OK "));
    assertTrue(secondResult.startsWith("RESULT OK "));
    first.login();
    second.login();
    assertEquals("RESULT OK 4:4", first.call("COUNTS"));
    assertEquals(4, commits());
    assertEquals("RESULT OK done", second.call("REVOKE"));
    assertEquals("RESULT OK 0:0", first.call("COUNTS"));
  }

  @Test
  void loginBehindPasswordChangeRechecksVerifiedVersion() throws Exception {
    rejectLoginAfter("PASSWORD");
  }

  @Test
  void loginBehindDisableRechecksEnabledState() throws Exception {
    rejectLoginAfter("DISABLE");
  }

  @Test
  void passwordChangeWaitingForLoginRevokesThatNewToken() throws Exception {
    revokeLoginBefore("PASSWORD");
  }

  @Test
  void disableWaitingForLoginRevokesThatNewToken() throws Exception {
    revokeLoginBefore("DISABLE");
  }

  private void revokeLoginBefore(String change) throws Exception {
    var first = worker("a", port(), Map.of());
    var second = worker("b", port(), Map.of());
    first.send("WAIT_LOGIN");
    assertEquals("LOCKED", first.next());
    second.send(change);
    awaitBlocked(schema + "_b");
    first.send("GO");
    String result = first.next();
    assertTrue(result.startsWith("RESULT OK "));
    String token = result.substring("RESULT OK ".length());
    assertEquals("RESULT OK done", second.next());
    assertEquals("RESULT OK false", first.call("VALID " + token));
    assertEquals("RESULT OK 0:0", first.call("COUNTS"));
  }

  private void rejectLoginAfter(String change) throws Exception {
    var first = worker("a", port(), Map.of());
    var second = worker("b", port(), Map.of());
    first.login();
    first.send("WAIT_" + change);
    assertEquals("LOCKED", first.next());
    second.send("LOGIN");
    awaitBlocked(schema + "_b");
    first.send("GO");
    assertEquals("RESULT OK done", first.next());
    assertTrue(second.next().startsWith("RESULT ERR "));
    assertEquals("RESULT OK 0:0", second.call("COUNTS"));
    assertEquals(1, commits(), "旧密码／禁用后不能登记新签发");
  }

  @Test
  void partialRevocationRollsBackBusinessStateAndRetryRevokesAll() throws Exception {
    String user = schema + "_acl";
    users.add(user);
    redis("ACL", "SETUSER", user, "on", ">" + user, "~*", "&*", "+@all");
    var first = worker("a", port(), Map.of("PROBE_REDIS_USER", user, "PROBE_REDIS_PASSWORD", user));
    var second = worker("b", port(), Map.of());
    String token = first.login();
    String secondToken = first.login();
    redis("ACL", "SETUSER", user, "-set");
    assertTrue(first.call("DISABLE").startsWith("RESULT ERR "));
    assertEquals(Boolean.TRUE, state().get("enabled"));
    assertEquals("RESULT OK false", second.call("VALID " + token));
    assertEquals("RESULT OK true", second.call("VALID " + secondToken));
    assertEquals("RESULT OK 1:2", second.call("COUNTS"));
    redis("ACL", "SETUSER", user, "+set");
    assertEquals("RESULT OK done", first.call("DISABLE"));
    assertEquals(Boolean.FALSE, state().get("enabled"));
    assertEquals("RESULT OK false", second.call("VALID " + token));
    assertEquals("RESULT OK false", second.call("VALID " + secondToken));
  }

  @Test
  void partialIssueCanLeaveNativeTokenWithoutTtlButCannotCommitSql() throws Exception {
    String user = schema + "_expiry";
    users.add(user);
    redis("ACL", "SETUSER", user, "on", ">" + user, "~*", "&*", "+@all");
    var first = worker("a", port(), Map.of("PROBE_REDIS_USER", user, "PROBE_REDIS_PASSWORD", user));
    var second = worker("b", port(), Map.of());
    first.login();
    redis("ACL", "SETUSER", user, "-pexpire", "(+pexpire ~" + sessionKey() + ")");
    assertTrue(first.call("LOGIN").startsWith("RESULT ERR "));
    assertEquals(1, commits());
    assertEquals("RESULT OK 2:2", second.call("COUNTS"));
    assertEquals("RESULT OK 1", second.call("IMMORTAL"), "SET 成功而原生过期脚本失败，不等同原子写入");
    assertEquals("RESULT OK done", second.call("REVOKE"));
    assertEquals("RESULT OK 0:0", second.call("COUNTS"));
  }

  @Test
  void realSqlCommitFailureDoesNotPublishTokenOrRollBackRedis() throws Exception {
    var first = worker("a", port(), Map.of());
    var second = worker("b", port(), Map.of());
    assertTrue(first.call("LOGIN_COMMIT_FAIL").startsWith("RESULT ERR "));
    assertEquals(0, commits(), "延迟唯一约束须在真实 COMMIT 失败并回滚 SQL");
    assertEquals("RESULT OK 1:1", second.call("COUNTS"), "SQL 回滚不等于 Redis 回滚");
    assertEquals("RESULT OK done", second.call("REVOKE"));
    assertEquals("RESULT OK 0:0", second.call("COUNTS"));
  }

  @Test
  void sqlCommitFailureAfterRevocationDoesNotRestoreOldSession() throws Exception {
    var first = worker("a", port(), Map.of());
    var second = worker("b", port(), Map.of());
    String token = first.login();
    assertTrue(first.call("DISABLE_COMMIT_FAIL").startsWith("RESULT ERR "));
    assertEquals(Boolean.TRUE, state().get("enabled"));
    assertEquals("RESULT OK false", second.call("VALID " + token));
  }

  @Test
  void lostLoginApplicationResponseDoesNotReturnTokenOrAutomaticallyRetry() throws Exception {
    var first = worker("a", port(), Map.of());
    var second = worker("b", port(), Map.of());
    assertTrue("EOF".equals(first.call("LOGIN_DROP_REPLY")), "SQL 提交已确认，但应用进程退出前未返回令牌");
    assertEquals(1, commits());
    assertEquals("RESULT OK 1:1", second.call("COUNTS"));
    assertEquals("RESULT OK done", second.call("REVOKE"));
  }

  @Test
  void lostApplicationResponseIsUnknownButRevocationRemainsEffective() throws Exception {
    var first = worker("a", port(), Map.of());
    var second = worker("b", port(), Map.of());
    String token = first.login();
    assertTrue("EOF".equals(first.call("DISABLE_DROP_REPLY")), "提交后进程退出，调用者未收到成功响应");
    assertEquals(Boolean.FALSE, state().get("enabled"));
    assertEquals("RESULT OK false", second.call("VALID " + token));
  }

  @Test
  void lostRedisReplyLeavesActualWriteButNoSqlCommitOrReturnedToken() throws Exception {
    try (var fault = new RedisWireFault(port())) {
      var first = worker("a", fault.port(), Map.of());
      var second = worker("b", port(), Map.of());
      first.login();
      fault.arm("SET", sessionKey(), RedisWireFault.Mode.DROP_REPLY);
      first.send("LOGIN");
      assertTrue(fault.awaitCaptured());
      assertTrue(first.next().startsWith("RESULT ERR "));
      assertEquals(1, commits());
      assertEquals("RESULT OK 1:2", second.call("COUNTS"), "索引已写入而调用者未收到响应");
      assertEquals("RESULT OK done", second.call("REVOKE"));
      assertEquals("RESULT OK 0:1", second.call("COUNTS"), "原生无 token 映射时可能残留 terminal 元数据");
    }
  }

  @Test
  void delayedRevokeMustNotHideLaterSuccessfulLoginFromRevocation() throws Exception {
    try (var fault = new RedisWireFault(port())) {
      var first = worker("a", fault.port(), Map.of());
      var second = worker("b", port(), Map.of());
      first.login();
      fault.arm("SET", sessionKey(), RedisWireFault.Mode.HOLD_WRITE);
      first.send("REVOKE");
      assertTrue(fault.awaitCaptured());
      assertTrue(first.next().startsWith("RESULT ERR "), "原生调用超时并释放 SQL 锁");
      String newerToken = second.login();
      assertEquals("RESULT OK 1:2", second.call("COUNTS"));
      fault.release();
      awaitCounts(second, "RESULT OK 1:0");
      assertEquals("RESULT OK done", second.call("REVOKE"));
      assertEquals(
          "RESULT OK false",
          second.call("VALID " + newerToken),
          "迟到的原生 SET 越过 SQL 锁生命周期，后续成功注销不能遗漏有效新令牌");
    }
  }

  private SessionProbeProcess worker(String name, int redisPort, Map<String, String> environment)
      throws Exception {
    var process =
        new SessionProbeProcess(schema, schema + "_" + name, type, redisPort, environment);
    workers.add(process);
    return process;
  }

  private Map<String, Object> state() {
    try (var session = database.openSession()) {
      return session.getMapper(NativeSessionProbe.Accounts.class).read();
    }
  }

  private int commits() {
    try (var session = database.openSession()) {
      return session.getMapper(NativeSessionProbe.Accounts.class).commits();
    }
  }

  private void awaitBlocked(String application) throws Exception {
    for (int i = 0; i < 100; i++) {
      try (var session = database.openSession()) {
        if (session.getMapper(NativeSessionProbe.Accounts.class).blocked(application) == 1) return;
      }
      Thread.sleep(20);
    }
    fail("未观测到 PostgreSQL 真实行锁等待");
  }

  private static void awaitCounts(SessionProbeProcess worker, String expected) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (worker.call("COUNTS").equals(expected)) return;
      Thread.sleep(20);
    }
    fail("未观测到延迟原生写入的服务端结果");
  }

  private String sessionKey() {
    return type + ":" + type + ":session:account";
  }

  private static int port() {
    return Integer.parseInt(NativeSessionProbe.required("REDIS_PORT"));
  }

  private static String redis(String... arguments) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                "docker",
                "exec",
                "-e",
                "REDISCLI_AUTH",
                NativeSessionProbe.required("REDIS_CONTAINER"),
                "redis-cli",
                "--no-auth-warning",
                "--raw"));
    command.addAll(List.of(arguments));
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("REDISCLI_AUTH", NativeSessionProbe.required("REDIS_PASSWORD"));
    var process = builder.start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      assertEquals(0, process.exitValue());
      assertFalse(output.startsWith("ERR") || output.startsWith("NOPERM"), "隔离故障准备失败");
      return output;
    } finally {
      process.destroyForcibly();
    }
  }
}
