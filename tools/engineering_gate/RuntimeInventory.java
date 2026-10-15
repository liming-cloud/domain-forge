import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** 读取真实装配及标准配置刷新结果，独立期望由 Python 门禁核对。 */
public final class RuntimeInventory {
  /** 工具无业务实例。 */
  private RuntimeInventory() {}

  /** 按模式运行装配或真实配置服务；连接只从私有文件读取。 */
  public static void main(String[] args) throws Exception {
    Map<String, Object> result = args[0].equals("assembly") ? assembly() : nacos(Path.of(args[2]));
    Files.writeString(Path.of(args[1]), JsonMapper.builder().build().writeValueAsString(result));
  }

  /** 使用框架条件及覆盖机制，记录实际组件调用和局部状态。 */
  private static Map<String, Object> assembly() {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("disabled", assemble(Map.of(), false, false));
    report.put(
        "default",
        assemble(Map.of("probe.enabled", "true", "probe.target", "declared-target"), false, false));
    report.put("missing", assemble(Map.of("probe.enabled", "true"), false, false));
    report.put(
        "selected",
        assemble(
            Map.of(
                "probe.enabled",
                "true",
                "probe.target",
                "declared-target",
                "probe.implementation",
                "unsupported"),
            false,
            false));
    report.put("override", assemble(Map.of("probe.enabled", "true"), true, false));
    report.put("conflict", assemble(Map.of("probe.enabled", "true"), true, true));
    return report;
  }

  /** 读取唯一公开类型；冲突不转换为偶然选中的实现。 */
  private static Map<String, Object> assemble(
      Map<String, Object> properties, boolean override, boolean conflict) {
    try (var context = start(properties, override, conflict)) {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("unaffected", context.getBean("unaffected"));
      state.put("count", context.getBeansOfType(RuntimeFixture.Connector.class).size());
      if (state.get("count").equals(0)) {
        state.put("state", "disabled");
      } else {
        try {
          var connector = context.getBean(RuntimeFixture.Connector.class);
          state.put("available", connector.available());
          state.put("result", connector.read());
          state.put("state", "available");
        } catch (org.springframework.beans.factory.NoUniqueBeanDefinitionException error) {
          state.put("state", "conflict");
        } catch (IllegalStateException error) {
          state.put("state", "unavailable");
        }
      }
      return state;
    }
  }

  /** 禁止读取用户配置，校准默认值仍可被权威 Nacos 属性源覆盖。 */
  private static ConfigurableApplicationContext start(
      Map<String, Object> supplied, boolean override, boolean conflict) {
    var app = new SpringApplication(RuntimeFixture.class);
    if (override) {
      app.addPrimarySources(java.util.List.of(RuntimeFixture.OverrideConfiguration.class));
    }
    if (conflict) {
      app.addPrimarySources(java.util.List.of(RuntimeFixture.ConflictConfiguration.class));
    }
    app.setBannerMode(Banner.Mode.OFF);
    app.setWebApplicationType(WebApplicationType.NONE);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("spring.cloud.nacos.config.enabled", "false");
    properties.put("spring.cloud.nacos.config.import-check.enabled", "false");
    properties.put("spring.config.location", "optional:classpath:/__gate_absent.properties");
    properties.put("spring.main.register-shutdown-hook", "false");
    properties.put("logging.level.root", "ERROR");
    properties.put("probe.value", "local-default");
    properties.put("probe.override", Boolean.toString(override));
    properties.put("probe.conflict", Boolean.toString(conflict));
    properties.putAll(supplied);
    app.setDefaultProperties(properties);
    return app.run();
  }

  /** 标准 ConfigData 与 RefreshScope 观察真实生效、隔离和应用执行失败恢复。 */
  private static Map<String, Object> nacos(Path file) throws Exception {
    Properties connection = new Properties();
    try (var input = Files.newInputStream(file)) {
      connection.load(input);
    }
    ConfigService publisher = NacosFactory.createConfigService(connection);
    Map<String, Object> report = new LinkedHashMap<>();
    String firstGroup = connection.getProperty("firstGroup");
    String secondGroup = connection.getProperty("secondGroup");
    publisher.publishConfig("gate.properties", firstGroup, "probe.value=alpha-1");
    publisher.publishConfig("gate.properties", secondGroup, "probe.value=beta-1");
    Path failure = file.resolveSibling("apply-failure");
    try (var alpha = start(remoteProperties(connection, firstGroup, failure), false, false);
        var beta = start(remoteProperties(connection, secondGroup, failure), false, false)) {
      report.put("initial", value(alpha));
      report.put("isolated", value(beta));
      publisher.publishConfig("gate.properties", firstGroup, "probe.value=alpha-2");
      await("updated", () -> value(alpha).equals("alpha-2"));
      report.put("updated", value(alpha));
      report.put("afterUpdateOther", value(beta));
      disconnected(file, publisher, firstGroup, alpha, report);
      applicationFailure(failure, publisher, firstGroup, alpha, report);
      publisher.publishConfig("gate.properties", firstGroup, "probe.value=alpha-4");
      await("recovery", () -> value(alpha).equals("alpha-4"));
      report.put("recovered", value(alpha));
      report.put("afterRecoveryOther", value(beta));
    } finally {
      publisher.shutDown();
    }
    return report;
  }

  /** 配置已到达后观察真实应用依赖故障，移除故障标记供后续恢复。 */
  private static void applicationFailure(
      Path failure,
      ConfigService publisher,
      String group,
      ConfigurableApplicationContext alpha,
      Map<String, Object> report)
      throws Exception {
    Files.createFile(failure);
    if (!publisher.publishConfig("gate.properties", group, "probe.value=alpha-3")) {
      throw new IllegalStateException("actual-config-publication-rejected");
    }
    await(
        "received-failure",
        () -> "alpha-3".equals(alpha.getEnvironment().getProperty("probe.value")));
    await("applied-failure", () -> value(alpha).equals("application-unavailable"));
    report.put("applicationFailure", value(alpha));
    report.put("receivedDuringFailure", alpha.getEnvironment().getProperty("probe.value"));
    Files.delete(failure);
  }

  /** 从独立客户端观察未缓存的已存在配置，确认连接故障而非内容不存在。 */
  private static void disconnected(
      Path file,
      ConfigService publisher,
      String firstGroup,
      ConfigurableApplicationContext alpha,
      Map<String, Object> report)
      throws Exception {
    if (!publisher.publishConfig("uncached-required.properties", firstGroup, "required-present")) {
      throw new IllegalStateException("required-config-not-published");
    }
    Files.writeString(file.resolveSibling("disconnect.request"), "pause-owned-service");
    await("disconnect", () -> Files.exists(file.resolveSibling("disconnect.ready")));
    boolean unavailable;
    try {
      unavailable = publisher.getConfig("uncached-required.properties", firstGroup, 1500) == null;
    } catch (com.alibaba.nacos.api.exception.NacosException error) {
      unavailable = true;
    }
    report.put("disconnectedFetchUnavailable", unavailable);
    report.put("cachedDuringDisconnect", value(alpha));
    Files.writeString(file.resolveSibling("reconnect.request"), "resume-owned-service");
    await("reconnect", () -> Files.exists(file.resolveSibling("reconnect.ready")));
    await("reconnect-publisher", () -> "UP".equals(publisher.getServerStatus()));
  }

  /** 引导输入只包含必要连接及隔离信息，不覆盖权威发布内容。 */
  private static Map<String, Object> remoteProperties(
      Properties connection, String group, Path failure) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("probe.remote", "true");
    values.put("probe.failure-file", failure.toString());
    values.put("spring.cloud.nacos.config.enabled", "true");
    values.put("spring.cloud.nacos.config.server-addr", connection.getProperty("serverAddr"));
    values.put("spring.cloud.nacos.config.username", connection.getProperty("username"));
    values.put("spring.cloud.nacos.config.password", connection.getProperty("password"));
    values.put("spring.config.import", "nacos:gate.properties?refreshEnabled=true&group=" + group);
    return values;
  }

  /** 调用实际能力对象；收到通知但对象不可用时保留失败。 */
  private static String value(ConfigurableApplicationContext context) {
    try {
      return context.getBean(RuntimeFixture.RemoteValue.class).read();
    } catch (org.springframework.beans.BeansException error) {
      return "application-unavailable";
    }
  }

  /** 轮询真实行为，固定观察期限内没有生效即报告具体失败阶段。 */
  private static void await(String stage, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("actual-application-did-not-converge:" + stage);
      }
      Thread.sleep(100);
    }
  }
}
