package org.domainforge.iam.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import cn.dev33.satoken.json.SaJsonTemplateForJackson3;
import cn.dev33.satoken.stp.StpLogic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.iam.infrastructure.persistence.session.SessionMapper;
import org.domainforge.iam.infrastructure.persistence.session.SqlSessionStore;
import org.domainforge.iam.infrastructure.security.JcaSecretOperations;
import org.domainforge.iam.infrastructure.security.SaNativeSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.config.Protocol;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 两个隔离 JVM 调用真实 IAM 授权代码；不是生产 DAO 接线或 Login 用例的替身。 */
public class CompositeSessionSafetyIT {
  /** 独立准备或改变 SQL 前置条件，禁止复制生产授权查询。 */
  public interface FixtureMapper {
    void seed(@Param("account") String account);

    void change(@Param("account") String account, @Param("change") String change);

    void grant(@Param("digest") String digest, @Param("change") String change);

    long version(@Param("account") String account);
  }

  private final List<Pipe> processes = new ArrayList<>();
  private final List<String> users = new ArrayList<>();
  private final UUID account = UUID.randomUUID();
  private Process lastStarted;
  private String schema;

  // 每项使用新 schema 和原生前缀，只有本次数据库容器参与清理。
  @BeforeEach
  void open() throws Exception {
    schema = "composite_" + UUID.randomUUID().toString().replace("-", "");
    script("composite/create.sql", schema, false);
    script("db/migration/V1__iam.sql", schema, true);
    script("db/migration/V2__login_policy.sql", schema, true);
  }

  @AfterEach
  void close() throws Exception {
    var failures = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (var process : processes) failures.add(process::close);
    for (var user : users) failures.add(() -> redis("ACL", "DELUSER", user));
    failures.add(() -> script("composite/drop.sql", schema, false));
    assertAll(failures);
  }

  // 原生仍可解析时，缺失／撤销／到期／禁用／版本不符逐项拒绝且不重建 SQL 许可。
  @Test
  void nativeValidityNeverReplacesCurrentSqlFactsAcrossInstances() throws Exception {
    var first = worker(redisPort());
    var second = worker(redisPort());
    first.ok("SEED " + account);
    for (String change : List.of("missing", "revoked", "expired", "disabled", "version")) {
      String token = issue(first, account);
      allowed(first, token, true);
      allowed(second, token, true);
      first.ok("CHANGE " + change + " " + token + " " + account);
      assertEquals("RESULT true", second.call("NATIVE " + token));
      allowed(first, token, false);
      allowed(second, token, false);
      allowed(first, token, false);
      first.ok("CHANGE enabled unused " + account);
    }
    String fresh = issue(second, account);
    allowed(first, fresh, true);
    allowed(second, fresh, true);
  }

  // 原样延迟真实 Session SET：A 的清理失败后 B 合法，迟到覆盖也不能复活 A。
  @Test
  void delayedCleanupKeepsCurrentLogoutScopeAndSqlRevocation() throws Exception {
    lateWrite("none");
  }

  // 此处独立改变版本只验证真实授权谓词；实际改密用例和生产顺序另行验证。
  @Test
  void delayedSessionWriteCannotReviveOldCredentialVersion() throws Exception {
    lateWrite("version");
  }

  // 禁用后再启用的旧版本不复活，其他账号保持可用，合法新授权能成功。
  @Test
  void delayedSessionWriteCannotReviveDisabledThenEnabledAccount() throws Exception {
    lateWrite("disabled");
  }

  private void lateWrite(String change) throws Exception {
    var wire = proxy();
    var first = worker(Integer.parseInt(wire.ready.substring("READY ".length())));
    var second = worker(redisPort());
    first.ok("SEED " + account);
    String old = issue(first, account);
    String retained = issue(first, account);
    UUID unrelated = UUID.randomUUID();
    second.ok("SEED " + unrelated);
    String other = issue(second, unrelated);
    first.ok("REVOKE " + old);
    wire.ok("ARM SET HOLD_WRITE " + schema + ":" + schema + ":session:" + account);
    first.send("CLEANUP " + old);
    assertEquals("RESULT true", wire.call("CAPTURED"));
    assertEquals("RESULT 1", first.next(), "清理超时必须计数，不能伪报 Redis 已回滚");
    String newer = issue(second, account);
    assertEquals("RESULT true", second.call("INDEXED " + newer + " " + account));
    if (!change.equals("none")) second.ok("CHANGE " + change + " unused " + account);
    wire.ok("RELEASE");
    awaitIndexMissing(second, newer);
    assertEquals("RESULT true", second.call("NATIVE " + newer));
    verifyLateOutcome(first, second, change, old, retained, newer, other);
  }

  private void awaitIndexMissing(Pipe worker, String token) throws Exception {
    for (int attempt = 0; ; attempt++) {
      if (worker.call("INDEXED " + token + " " + account).equals("RESULT false")) return;
      assertTrue(attempt < 100, "未观察到真实迟到帧覆盖新终端索引");
      Thread.sleep(20);
    }
  }

  private void verifyLateOutcome(
      Pipe first,
      Pipe second,
      String change,
      String old,
      String retained,
      String newer,
      String other)
      throws Exception {
    for (var instance : List.of(first, second)) {
      allowed(instance, old, false);
      allowed(instance, retained, change.equals("none"));
      allowed(instance, newer, change.equals("none"));
      allowed(instance, other, true);
    }
    if (!change.equals("none")) {
      second.ok("CHANGE enabled unused " + account);
      allowed(first, newer, false);
      allowed(second, retained, false);
      String fresh = issue(second, account);
      allowed(first, fresh, true);
      allowed(second, fresh, true);
    } else {
      second.ok("REVOKE " + newer);
      second.ok("CLEANUP_OK " + newer);
      allowed(first, newer, false);
      allowed(second, newer, false);
      allowed(first, retained, true);
    }
  }

  // 读取不补建 SQL：存在原生孤儿令牌时，每个实例都拒绝，不能恢复为已登录。
  @Test
  void nativeOrphanNeverCreatesSqlGrantOnRead() throws Exception {
    var first = worker(redisPort());
    var second = worker(redisPort());
    first.ok("SEED " + account);
    String token = first.value("ISSUE " + account);
    assertEquals("RESULT true", second.call("NATIVE " + token));
    allowed(first, token, false);
    allowed(second, token, false);
    allowed(first, token, false);
  }

  // 真实子 JVM 尚未报告 READY 时父线程中断，构造失败也必须回收未登记进列表的进程。
  @Test
  void interruptedWorkerStartupDoesNotLeaveChildJvm() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> worker(redisPort()));
    } finally {
      Thread.interrupted();
    }
    assertNotNull(lastStarted, "必须实际启动子 JVM 后再触发等待中断");
    assertFalse(lastStarted.isAlive(), "启动等待失败后不能留下本次子 JVM");
  }

  // 原生 DEL／后续 SET 被服务器真实拒绝时，SQL 已撤销仍须拒绝；清理失败独立计数。
  @Test
  void deniedNativeCleanupCannotUndoSqlRevocation() throws Exception {
    var observer = worker(redisPort());
    observer.ok("SEED " + account);
    for (String denied : List.of("del", "set")) {
      String user = aclUser(denied);
      var restricted =
          worker(redisPort(), Map.of("M2_REDIS_USER", user, "M2_REDIS_PASSWORD", user));
      String token = issue(restricted, account);
      String other = issue(observer, account);
      restricted.ok("REVOKE " + token);
      redis("ACL", "SETUSER", user, "-" + denied);
      assertEquals("RESULT 1", restricted.call("CLEANUP " + token));
      assertEquals("RESULT " + denied.equals("del"), observer.call("NATIVE " + token));
      allowed(observer, token, false);
      allowed(restricted, token, false);
      allowed(observer, other, true);
    }
  }

  // 过期步骤真实失败会留下无 TTL 的原生令牌，但既有 SQL 判定不得为它补建许可。
  @Test
  void partialIssueWithFailedExpiryRemainsAnUnauthorizedOrphan() throws Exception {
    String user = aclUser("expiry");
    var restricted = worker(redisPort(), Map.of("M2_REDIS_USER", user, "M2_REDIS_PASSWORD", user));
    var observer = worker(redisPort());
    observer.ok("SEED " + account);
    String existing = issue(restricted, account);
    redis(
        "ACL",
        "SETUSER",
        user,
        "-pexpire",
        "(+pexpire ~" + schema + ":" + schema + ":session:" + account + ")");
    assertTrue(restricted.call("ISSUE " + account).startsWith("RESULT ERROR "), "签发失败不能返回 token");
    var orphan = nativeTokens().stream().filter(token -> !token.equals(existing)).toList();
    assertEquals(1, orphan.size(), "一次失败不自动重试生成多个令牌");
    assertEquals("-1", redis("TTL", schema + ":" + schema + ":token:" + orphan.getFirst()));
    assertEquals("RESULT true", observer.call("NATIVE " + orphan.getFirst()));
    allowed(restricted, orphan.getFirst(), false);
    allowed(observer, orphan.getFirst(), false);
    allowed(observer, existing, true);
  }

  // 原生响应丢失时不返回成功或令牌；失败不产生可完整授权的新身份。
  @Test
  void lostRedisReplyDoesNotBecomeACompleteIdentity() throws Exception {
    var wire = proxy();
    var first = worker(Integer.parseInt(wire.ready.substring("READY ".length())));
    var second = worker(redisPort());
    first.ok("SEED " + account);
    String token = issue(first, account);
    wire.ok("ARM SET DROP_REPLY " + schema + ":" + schema + ":session:" + account);
    first.send("ISSUE " + account);
    assertEquals("RESULT true", wire.call("CAPTURED"));
    assertTrue(first.next().startsWith("RESULT ERROR "), "不能把 Redis 应答丢失报为签发成功");
    assertEquals(1, nativeTokens().size(), "Account-Session 应答丢失发生在新 token 映射前");
    allowed(second, token, true);
    wire.ok("RELEASE");
  }

  private String aclUser(String suffix) throws Exception {
    String user = schema + "_" + suffix;
    users.add(user);
    redis("ACL", "SETUSER", user, "on", ">" + user, "~" + schema + ":*", "&*", "+@all");
    return user;
  }

  private List<String> nativeTokens() throws Exception {
    String prefix = schema + ":" + schema + ":token:";
    return redis("--scan", "--pattern", prefix + "*")
        .lines()
        .filter(key -> !key.isBlank())
        .map(key -> key.substring(prefix.length()))
        .toList();
  }

  private static String redis(String... arguments) throws Exception {
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
    builder.environment().put("REDISCLI_AUTH", required("IAM_REDIS_PASSWORD"));
    var process = builder.start();
    try {
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "本次 Redis 观察超时");
      String value =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      assertEquals(0, process.exitValue());
      assertFalse(value.startsWith("ERR"), "本次 Redis 控制命令失败");
      return value;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private String issue(Pipe worker, UUID id) throws Exception {
    String token = worker.value("ISSUE " + id);
    worker.ok("GRANT " + token + " " + id);
    return token;
  }

  private void allowed(Pipe worker, String token, boolean expected) throws Exception {
    assertEquals("RESULT " + expected, worker.call("CURRENT " + token));
  }

  private Pipe proxy() throws Exception {
    String classpath = required("M2_WIRE_CLASSPATH");
    var process =
        new Pipe(
            classpath,
            "org.domainforge.access.backend.internal.RedisWireFault",
            Integer.toString(redisPort()));
    processes.add(process);
    return process;
  }

  private Pipe worker(int redisPort) throws Exception {
    return worker(redisPort, Map.of());
  }

  private Pipe worker(int redisPort, Map<String, String> environment) throws Exception {
    // 目标验证 JVM 物理移除旧 DAO／Lettuce，不能仅用未调用它们来宣称没有旧装配。
    String classpath =
        String.join(
            File.pathSeparator,
            Arrays.stream(System.getProperty("surefire.test.class.path").split(File.pathSeparator))
                .filter(
                    path ->
                        !path.contains("/lettuce-core/")
                            && !path.contains("/spring-data-redis/")
                            && !path.contains("/sa-token-redis-template/"))
                .toList());
    var process =
        new Pipe(
            classpath,
            getClass().getName(),
            environment,
            started -> lastStarted = started,
            schema,
            Integer.toString(redisPort));
    processes.add(process);
    return process;
  }

  /** 只通过父进程私有管道接收测试命令，令牌不写日志或报告。 */
  public static void main(String[] arguments) throws Exception {
    assertThrows(ClassNotFoundException.class, () -> Class.forName("io.lettuce.core.RedisClient"));
    try (var target = new Target(arguments);
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      System.out.println("READY");
      for (String line; (line = input.readLine()) != null; ) {
        try {
          System.out.println("RESULT " + target.observe(line.split(" ")));
        } catch (Exception failure) {
          System.out.println("RESULT ERROR " + failure.getClass().getSimpleName());
        }
      }
    }
  }

  private record Database(
      SqlSessionStore sessions, FixtureMapper fixture, TransactionTemplate transaction) {}

  private static Database database(String schema) throws Exception {
    var source =
        new DriverManagerDataSource(
            url(schema), required("IAM_DB_USER"), required("IAM_DB_PASSWORD"));
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(source);
    var configuration = new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setArgNameBasedConstructorAutoMapping(true);
    factory.setConfiguration(configuration);
    factory.setMapperLocations(
        new ClassPathResource("mapper/session/SessionMapper.xml"),
        new ClassPathResource("mapper/fixture/CompositeSessionMapper.xml"));
    var template = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    var manager = new DataSourceTransactionManager(source);
    return new Database(
        new SqlSessionStore(template.getMapper(SessionMapper.class), manager),
        template.getMapper(FixtureMapper.class),
        new TransactionTemplate(manager));
  }

  /** 显式的目标测试装配；只拥有一个 RedissonClient，无 Spring 自动装配或授权算法副本。 */
  private static final class Target implements AutoCloseable {
    private final Database database;
    private final org.redisson.api.RedissonClient client;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final JcaSecretOperations secrets = new JcaSecretOperations(new byte[32]);
    private final StpLogic logic;
    private final SaNativeSessions nativeSessions;
    private final CurrentAuthorization authorization;

    Target(String[] arguments) throws Exception {
      database = database(arguments[0]);
      var config = new Config().setProtocol(Protocol.RESP3).setThreads(2).setNettyThreads(2);
      config
          .useSingleServer()
          .setAddress("redis://127.0.0.1:" + arguments[1])
          .setUsername(System.getenv().getOrDefault("M2_REDIS_USER", "default"))
          .setPassword(
              System.getenv().getOrDefault("M2_REDIS_PASSWORD", required("IAM_REDIS_PASSWORD")))
          .setRetryAttempts(0)
          .setTimeout(800)
          .setConnectTimeout(2000)
          .setConnectionMinimumIdleSize(1)
          .setConnectionPoolSize(2);
      client = Redisson.create(config);
      SaManager.setSaTokenDao(new SaTokenDaoForRedisson(client));
      SaManager.setSaJsonTemplate(new SaJsonTemplateForJackson3());
      logic =
          new StpLogic(arguments[0])
              .setConfig(
                  new SaTokenConfig()
                      .setTokenName(arguments[0])
                      .setIsShare(false)
                      .setIsPrint(false)
                      .setIsLog(false)
                      .setActiveTimeout(-1));
      nativeSessions = new SaNativeSessions(logic, metrics);
      authorization = new CurrentAuthorization(nativeSessions, database.sessions(), secrets);
    }

    String observe(String[] fields) {
      return switch (fields[0]) {
        case "SEED", "GRANT", "REVOKE", "CHANGE" -> prepare(fields);
        case "ISSUE" -> nativeSessions.issue(UUID.fromString(fields[1]));
        case "CURRENT" -> current(fields[1]);
        case "NATIVE" -> Boolean.toString(nativeSessions.subject(fields[1]) != null);
        case "INDEXED" ->
            Boolean.toString(
                logic.getTerminalListByLoginId(fields[2]).stream()
                    .anyMatch(terminal -> terminal.getTokenValue().equals(fields[1])));
        case "CLEANUP", "CLEANUP_OK" -> {
          nativeSessions.cleanup(fields[1]);
          yield fields[0].equals("CLEANUP_OK")
              ? "OK"
              : Long.toString((long) metrics.counter("iam.session.cleanup.failures").count());
        }
        default -> throw new IllegalArgumentException("未知组合验证操作");
      };
    }

    private String current(String token) {
      try {
        authorization.current(token);
        return "true";
      } catch (IamException failure) {
        if (failure.failure() != IamFailure.UNAUTHENTICATED) throw failure;
        return "false";
      }
    }

    private String prepare(String[] fields) {
      switch (fields[0]) {
        case "SEED" -> database.fixture().seed(fields[1]);
        case "GRANT" ->
            database
                .transaction()
                .executeWithoutResult(
                    status ->
                        database
                            .sessions()
                            .insert(
                                new SessionGrant(
                                    secrets.digest(fields[1]),
                                    UUID.fromString(fields[2]),
                                    database.fixture().version(fields[2]),
                                    Instant.now().plusSeconds(60),
                                    false)));
        case "REVOKE" ->
            database
                .transaction()
                .executeWithoutResult(
                    status -> database.sessions().revoke(secrets.digest(fields[1])));
        case "CHANGE" -> {
          if (List.of("missing", "revoked", "expired").contains(fields[1]))
            database.fixture().grant(secrets.digest(fields[2]), fields[1]);
          else database.fixture().change(fields[3], fields[1]);
        }
        default -> throw new IllegalArgumentException("未知 SQL 前置条件");
      }
      return "OK";
    }

    @Override
    public void close() {
      metrics.close();
      client.shutdown();
    }
  }

  private static int redisPort() {
    return Integer.parseInt(required("IAM_REDIS_PORT"));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("缺少隔离组合输入 " + name);
    return value;
  }

  private static String url(String schema) {
    return required("IAM_DB_URL") + "?currentSchema=" + schema;
  }

  private static void script(String resource, String schema, boolean scoped) throws Exception {
    if (!schema.matches("composite_[a-f0-9]{32}"))
      throw new IllegalArgumentException("隔离 schema 无效");
    try (var connection =
            DriverManager.getConnection(
                scoped ? url(schema) : required("IAM_DB_URL"),
                required("IAM_DB_USER"),
                required("IAM_DB_PASSWORD"));
        var input = CompositeSessionSafetyIT.class.getResourceAsStream("/" + resource)) {
      var runner = new ScriptRunner(connection);
      runner.setAutoCommit(true);
      runner.setStopOnError(true);
      runner.setLogWriter(null);
      runner.setErrorLogWriter(null);
      runner.runScript(
          new StringReader(
              new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
                  .replace("${schema}", schema)));
    }
  }

  /** 子进程只保留控制标记；原生日志不进入报告，响应限时且退出时回收。 */
  private static final class Pipe implements AutoCloseable {
    private final Process process;
    private final BufferedWriter input;
    private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>(32);
    private final String ready;

    Pipe(String classpath, String main, String... arguments) throws Exception {
      this(classpath, main, Map.of(), ignored -> {}, arguments);
    }

    Pipe(
        String classpath,
        String main,
        Map<String, String> environment,
        java.util.function.Consumer<Process> started,
        String... arguments)
        throws Exception {
      var command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin/java").toString(),
                  "-Xmx256m",
                  "-XX:ActiveProcessorCount=2",
                  "-cp",
                  classpath,
                  main));
      command.addAll(List.of(arguments));
      var builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().putAll(environment);
      process = builder.start();
      started.accept(process);
      input =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      Thread.ofVirtual()
          .start(
              () -> {
                try (var reader =
                    new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                  for (String line; (line = reader.readLine()) != null; )
                    if (line.startsWith("READY") || line.startsWith("RESULT ")) output.put(line);
                } catch (Exception ignored) {
                  /* 故障只通过 EOF 报告，不打印原始秘密。 */
                } finally {
                  output.offer("EOF");
                }
              });
      try {
        ready = next();
        if (!ready.startsWith("READY")) fail("隔离组合进程启动失败");
      } catch (Exception | AssertionError failure) {
        try {
          close();
        } catch (Exception | AssertionError cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    void send(String command) throws IOException {
      input.write(command);
      input.newLine();
      input.flush();
    }

    String next() throws InterruptedException {
      String value = output.poll(15, TimeUnit.SECONDS);
      assertNotNull(value, "隔离组合进程超时");
      return value;
    }

    String call(String command) throws Exception {
      send(command);
      return next();
    }

    void ok(String command) throws Exception {
      assertEquals("RESULT OK", call(command));
    }

    String value(String command) throws Exception {
      String value = call(command);
      assertTrue(value.startsWith("RESULT ") && !value.startsWith("RESULT ERROR"), "原生签发未成功");
      return value.substring("RESULT ".length());
    }

    @Override
    public void close() throws Exception {
      try {
        input.close();
      } catch (IOException ignored) {
        /* 故障子进程的管道可能已关闭；仍须执行进程回收。 */
      }
      try {
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        assertTrue(process.waitFor(3, TimeUnit.SECONDS), "本次隔离进程未回收");
      } finally {
        if (process.isAlive()) process.destroyForcibly();
        output.clear();
      }
    }
  }
}
