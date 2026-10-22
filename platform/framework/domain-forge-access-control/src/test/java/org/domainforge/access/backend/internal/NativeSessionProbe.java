package org.domainforge.access.backend.internal;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import cn.dev33.satoken.json.SaJsonTemplateForJackson3;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.Protocol;

/** 仅用于隔离 JVM 实证：原生行锁与会话调用，不是 IAM 实现或公开客户端协议。 */
public final class NativeSessionProbe {
  public interface Accounts {
    Map<String, Object> lock();

    Map<String, Object> read();

    void disable();

    void password();

    void record(String digest);

    void conflict();

    int commits();

    int blocked(String application);
  }

  static SqlSessionFactory database(String schema, String application) {
    var source =
        new UnpooledDataSource(
            "org.postgresql.Driver",
            url() + "?currentSchema=" + schema + "&ApplicationName=" + application,
            "domainforge",
            required("POSTGRES_PASSWORD"));
    var configuration =
        new Configuration(new Environment("probe", new JdbcTransactionFactory(), source));
    try (var input = NativeSessionProbe.class.getResourceAsStream("/session-probe/accounts.xml")) {
      new XMLMapperBuilder(input, configuration, "accounts.xml", configuration.getSqlFragments())
          .parse();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  static void schema(String name, String operation) throws Exception {
    if (!name.matches("probe_[a-f0-9]{32}")) throw new IllegalArgumentException("隔离 schema 名称无效");
    try (var input =
            NativeSessionProbe.class.getResourceAsStream("/session-probe/" + operation + ".sql");
        var connection =
            DriverManager.getConnection(url(), "domainforge", required("POSTGRES_PASSWORD"));
        var statement = connection.createStatement()) {
      String sql =
          new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("${schema}", name);
      for (String part : sql.split(";")) if (!part.isBlank()) statement.execute(part);
    }
  }

  public static void main(String[] args) throws Exception {
    var factory = database(args[0], args[1]);
    var config = new Config().setProtocol(Protocol.RESP3).setThreads(2).setNettyThreads(2);
    config
        .useSingleServer()
        .setAddress("redis://127.0.0.1:" + args[3])
        .setUsername(System.getenv().getOrDefault("PROBE_REDIS_USER", "default"))
        .setPassword(
            System.getenv().getOrDefault("PROBE_REDIS_PASSWORD", required("REDIS_PASSWORD")))
        .setRetryAttempts(0)
        .setTimeout(800)
        .setConnectTimeout(2000)
        .setConnectionMinimumIdleSize(1)
        .setConnectionPoolSize(2);
    RedissonClient client = Redisson.create(config);
    SaManager.setSaTokenDao(new SaTokenDaoForRedisson(client));
    SaManager.setSaJsonTemplate(new SaJsonTemplateForJackson3());
    var logic =
        new StpLogic(args[2])
            .setConfig(
                new SaTokenConfig()
                    .setTokenName(args[2])
                    .setIsShare(false)
                    .setTimeout(60)
                    .setActiveTimeout(-1));
    try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      System.out.println("READY");
      for (String line; (line = input.readLine()) != null; ) {
        try {
          String result = operation(factory, logic, line, input);
          System.out.println("RESULT OK " + result);
        } catch (Exception failure) {
          // 不输出原生异常文本，避免测试记录包含令牌、SQL 参数或连接凭据。
          System.out.println("RESULT ERR " + failure.getClass().getSimpleName());
        }
      }
    } finally {
      client.shutdown();
    }
  }

  private static String operation(
      SqlSessionFactory factory, StpLogic logic, String command, BufferedReader input)
      throws Exception {
    if (command.startsWith("VALID ")) {
      return Boolean.toString("account".equals(logic.getLoginIdByToken(command.substring(6))));
    }
    if (command.equals("IMMORTAL")) {
      return Long.toString(
          logic.searchTokenValue("", 0, -1, false).stream()
              .filter(key -> SaManager.getSaTokenDao().getTimeout(key) == -1)
              .count());
    }
    if (command.equals("COUNTS")) {
      return logic.searchTokenValue("", 0, -1, false).size()
          + ":"
          + logic.getTerminalListByLoginId("account").size();
    }
    try (var session = factory.openSession(false)) {
      var accounts = session.getMapper(Accounts.class);
      var account = accounts.lock();
      if (command.startsWith("WAIT_")) {
        System.out.println("LOCKED");
        if (!"GO".equals(input.readLine())) throw new IllegalStateException("未收到测试继续信号");
        command = command.substring(5);
      }
      String token = changeAccount(accounts, account, logic, command);
      if (command.endsWith("COMMIT_FAIL")) {
        accounts.conflict();
        accounts.conflict();
      }
      session.commit();
      if (command.endsWith("DROP_REPLY")) Runtime.getRuntime().halt(0);
      return token;
    }
  }

  private static String changeAccount(
      Accounts accounts, Map<String, Object> account, StpLogic logic, String command)
      throws Exception {
    if (command.contains("LOGIN")) {
      if (!Boolean.TRUE.equals(account.get("enabled"))
          || !Integer.valueOf(1).equals(account.get("version")))
        throw new IllegalStateException("账号状态或已验证凭据版本变化");
      String token =
          logic.createLoginSession(
              "account", new SaLoginParameter().setIsShare(false).setTimeout(60));
      accounts.record(
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(token.getBytes(StandardCharsets.UTF_8))));
      return token;
    }
    // 最小候选排序：持同一账号行锁，原生逐 token 撤销成功才提交 SQL。
    for (var terminal : logic.getTerminalListByLoginId("account"))
      logic.logoutByTokenValue(terminal.getTokenValue());
    if (command.contains("DISABLE")) accounts.disable();
    if (command.contains("PASSWORD")) accounts.password();
    return "done";
  }

  private static String url() {
    return "jdbc:postgresql://127.0.0.1:" + required("POSTGRES_PORT") + "/domainforge";
  }

  static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("缺少隔离测试环境 " + name);
    return value;
  }
}
