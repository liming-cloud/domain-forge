import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.*;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException;
import org.postgresql.PGConnection;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.w3c.dom.Element;

/** 观察实际适配调用、原生执行和独立数据库状态，不补插件、事务或业务实现。 */
public final class PersistenceAdapterInventory {
  private static final Map<String, String> TABLES = new LinkedHashMap<>();

  static {
    TABLES.put("gate_adapter", "id,tenant,quantity,day,local_at,instant_at,note,state,revision");
    TABLES.put("gate_unique", "code");
    TABLES.put("gate_batch", "id,code");
  }

  /** 阻止将一次性观察器实例化为产品对象。 */
  private PersistenceAdapterInventory() {}

  /** 读取私有调用合同并输出有限原生观察。 */
  public static void main(String[] args) throws Exception {
    var parser = DocumentBuilderFactory.newInstance();
    parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var contract =
        parser.newDocumentBuilder().parse(Path.of(args[0]).toFile()).getDocumentElement();
    try (var stream = Files.newOutputStream(Path.of(args[1]))) {
      var output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("adapter-observation");
      application(contract, output);
      output.writeEndElement();
      output.close();
    }
  }

  /** 实际启动应用；启动失败保留类型化事实，不创建替代资源。 */
  private static void application(Element contract, XMLStreamWriter output) throws Exception {
    var app = new SpringApplication(Class.forName(contract.getAttribute("application")));
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setBannerMode(Banner.Mode.OFF);
    app.setDefaultProperties(Map.of("logging.level.root", "OFF"));
    ConfigurableApplicationContext context;
    try {
      context = app.run();
    } catch (Exception failure) {
      output.writeAttribute("started", "false");
      attributes(output, failure(failure));
      if (contract.getAttribute("profile").equals("migration")) {
        migrationFailure(failure, output);
        migrationSnapshots(output);
      }
      return;
    }
    try (context) {
      output.writeAttribute("started", "true");
      operations(context, contract, output);
    }
  }

  /** 校验实际资源身份后安装透明记录器，不改变 SQL 或应用返回。 */
  private static void operations(
      ConfigurableApplicationContext context, Element contract, XMLStreamWriter output)
      throws Exception {
    Object data = context.getBean(contract.getAttribute("datasource"));
    Object session = context.getBean(contract.getAttribute("factory"));
    Object manager = context.getBean(contract.getAttribute("manager"));
    output.writeStartElement("resources");
    if (!(data instanceof DynamicRoutingDataSource routing)
        || !(session instanceof SqlSessionFactory factory)
        || !(manager instanceof DataSourceTransactionManager transaction)) {
      output.writeAttribute("supported", "false");
      output.writeEndElement();
      return;
    }
    output.writeAttribute(
        "mybatis-plus", String.valueOf(factory.getConfiguration() instanceof MybatisConfiguration));
    output.writeAttribute(
        "factory-bound",
        String.valueOf(factory.getConfiguration().getEnvironment().getDataSource() == routing));
    output.writeAttribute("manager-bound", String.valueOf(transaction.getDataSource() == routing));
    output.writeEndElement();
    if (contract.getAttribute("profile").equals("migration")) {
      migrations(context, output);
      migrationSnapshots(output);
      return;
    }
    var trace = new NativeTrace();
    factory.getConfiguration().addInterceptor(trace);
    output.writeStartElement("case");
    output.writeAttribute("name", "initial");
    snapshots(output);
    output.writeEndElement();
    Object bean = context.getBean(contract.getAttribute("bean"));
    var operations = contract.getElementsByTagName("operation");
    for (int index = 0; index < operations.getLength(); index++) {
      runCase(bean, (Element) operations.item(index), trace, output);
    }
  }

  /** 同一竞争场景在两个线程调用两个真实事务，结束后才读提交状态。 */
  private static void runCase(
      Object bean, Element operation, NativeTrace trace, XMLStreamWriter output) throws Exception {
    String name = operation.getAttribute("name");
    List<Call> calls = new ArrayList<>();
    if (operation.getAttribute("parallel").equals("true")) {
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first = pool.submit(() -> invoke(bean, name, trace));
        var second = pool.submit(() -> invoke(bean, name, trace));
        calls.add(first.get(10, TimeUnit.SECONDS));
        calls.add(second.get(10, TimeUnit.SECONDS));
      }
    } else {
      calls.add(invoke(bean, name, trace));
    }
    output.writeStartElement("case");
    output.writeAttribute("name", name);
    for (Call call : calls) {
      output.writeStartElement("call");
      attributes(output, call.result());
      for (var event : call.events()) {
        output.writeStartElement("trace");
        attributes(output, event);
        output.writeEndElement();
      }
      output.writeEndElement();
    }
    snapshots(output);
    output.writeEndElement();
  }

  /** 仅调用公开的固定无参入口；代理负责提交或回滚后才返回。 */
  private static Call invoke(Object bean, String method, NativeTrace trace) {
    List<Map<String, String>> events = new ArrayList<>();
    trace.events.set(events);
    Map<String, String> result = new LinkedHashMap<>();
    try {
      var target = bean.getClass().getMethod(method);
      if (target.getReturnType() != String.class)
        throw new IllegalArgumentException("unsupported return type");
      result.put("result", String.valueOf(target.invoke(bean)));
    } catch (InvocationTargetException failure) {
      result.putAll(failure(failure.getCause()));
    } catch (ReflectiveOperationException failure) {
      result.putAll(failure(failure));
    } finally {
      result.put("lookup", String.valueOf(DynamicDataSourceContextHolder.peek()));
      trace.events.remove();
    }
    return new Call(result, events);
  }

  /** 只保留异常类型和 SQLSTATE，不复制异常消息或连接秘密。 */
  private static Map<String, String> failure(Throwable error) {
    var result = new LinkedHashMap<String, String>();
    result.put("exception", error.getClass().getName());
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null)
        result.put("sqlstate", sql.getSQLState());
    }
    return result;
  }

  /** 通过独立只读连接读取两个本轮目标，不能由应用回报代替。 */
  private static void snapshots(XMLStreamWriter output) throws Exception {
    for (String alias : List.of("master", "inventory")) {
      try (var connection = connection(alias)) {
        for (var table : TABLES.entrySet()) {
          snapshot(connection, alias, table.getKey(), table.getValue(), output);
        }
      }
    }
  }

  /** 创建只读快照连接并明确时间点文本的显示时区。 */
  private static Connection connection(String alias) throws SQLException {
    String prefix = "DF_GATE_" + alias.toUpperCase(Locale.ROOT) + "_";
    var connection =
        DriverManager.getConnection(
            System.getenv(prefix + "URL"),
            System.getenv(prefix + "USER"),
            System.getenv(prefix + "PASSWORD"));
    connection.setReadOnly(true);
    try (var statement = connection.createStatement()) {
      statement.execute("SET TIME ZONE 'UTC'");
    }
    return connection;
  }

  /** 保存有限真实行值和 JDBC 列元数据，仅输出到私有文件。 */
  private static void snapshot(
      Connection connection, String alias, String table, String columns, XMLStreamWriter output)
      throws Exception {
    output.writeStartElement("snapshot");
    output.writeAttribute("database", alias);
    output.writeAttribute("table", table);
    try (var statement = connection.createStatement();
        var results =
            statement.executeQuery(
                "SELECT " + columns + " FROM public." + table + " ORDER BY 1 LIMIT 65")) {
      output.writeAttribute("complete", "true");
      var metadata = results.getMetaData();
      for (int index = 1; index <= metadata.getColumnCount(); index++) {
        output.writeStartElement("column");
        output.writeAttribute("type", metadata.getColumnTypeName(index));
        output.writeAttribute("precision", String.valueOf(metadata.getPrecision(index)));
        output.writeAttribute("scale", String.valueOf(metadata.getScale(index)));
        output.writeEndElement();
      }
      while (results.next()) {
        output.writeStartElement("row");
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
          output.writeStartElement("v");
          String value = results.getString(index);
          if (value == null) output.writeAttribute("null", "true");
          else output.writeCharacters(value);
          output.writeEndElement();
        }
        output.writeEndElement();
      }
    } catch (SQLException failure) {
      output.writeAttribute("sqlstate", String.valueOf(failure.getSQLState()));
    }
    output.writeEndElement();
  }

  /** 启动失败时仍独立保留每个资源的真实迁移历史与数据状态。 */
  private static void migrationSnapshots(XMLStreamWriter output) throws Exception {
    for (String alias : List.of("master", "inventory")) {
      try (var connection = connection(alias)) {
        snapshot(connection, alias, "flyway_schema_history", "version,script,success", output);
        snapshot(connection, alias, "gate_marker", "*", output);
      }
    }
  }

  /** 绑定真实失败脚本来源；无关启动异常不能作为迁移回滚证据。 */
  private static void migrationFailure(Throwable error, XMLStreamWriter output) throws Exception {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof FlywaySqlScriptException script) {
        output.writeStartElement("migration-failure");
        migrationResource(
            Path.of(script.getResource().getAbsolutePathOnDisk()),
            "db/inventory/V2__upgrade.sql",
            Thread.currentThread().getContextClassLoader(),
            output);
        output.writeEndElement();
        return;
      }
    }
  }

  /** 将实际物理资源和预期类路径绑定，并读取独立文件摘要。 */
  private static void migrationResource(
      Path resource, String expected, ClassLoader loader, XMLStreamWriter output) throws Exception {
    var actual = loader.getResource(expected);
    boolean bound =
        actual != null && resource.toRealPath().equals(Path.of(actual.toURI()).toRealPath());
    output.writeAttribute("resource", bound ? expected : "unbound");
    output.writeAttribute(
        "resource-sha256",
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(resource))));
  }

  /** 核对应用自己的迁移 Bean、原生校验与实际类路径资源，不调用 migrate。 */
  private static void migrations(ConfigurableApplicationContext context, XMLStreamWriter output)
      throws Exception {
    for (String alias : List.of("master", "inventory")) {
      if (!context.containsBean(alias + "Flyway")) continue;
      var flyway = context.getBean(alias + "Flyway", Flyway.class);
      output.writeStartElement("flyway");
      output.writeAttribute("database", alias);
      output.writeAttribute(
          "valid", String.valueOf(flyway.validateWithResult().validationSuccessful));
      try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
        output.writeAttribute("jdbc-url", connection.getMetaData().getURL());
      }
      for (var migration : flyway.info().all()) {
        output.writeStartElement("migration");
        output.writeAttribute("version", String.valueOf(migration.getVersion()));
        output.writeAttribute("script", migration.getScript());
        output.writeAttribute("state", migration.getState().name());
        Path resource = Path.of(migration.getPhysicalLocation());
        String expected = "db/" + alias + "/" + migration.getScript();
        migrationResource(resource, expected, context.getClassLoader(), output);
        output.writeEndElement();
      }
      output.writeEndElement();
    }
  }

  /** 序列化有限属性，不执行属性内容。 */
  private static void attributes(XMLStreamWriter output, Map<String, String> values)
      throws XMLStreamException {
    for (var value : values.entrySet()) output.writeAttribute(value.getKey(), value.getValue());
  }

  /** 单次实际调用的结果与线程隔离记录。 */
  private record Call(Map<String, String> result, List<Map<String, String>> events) {}

  /** 透明记录原生准备、执行及批次刷新，不主动取得应用连接。 */
  @Intercepts({
    @Signature(
        type = Executor.class,
        method = "update",
        args = {MappedStatement.class, Object.class}),
    @Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(
        type = Executor.class,
        method = "flushStatements",
        args = {}),
    @Signature(
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class}),
    @Signature(
        type = StatementHandler.class,
        method = "batch",
        args = {Statement.class})
  })
  private static final class NativeTrace implements Interceptor {
    private final ThreadLocal<List<Map<String, String>>> events = new ThreadLocal<>();

    /** 在原生执行点记录元数据后原样转交，绝不替应用制造成功或失败。 */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      List<Map<String, String>> list = events.get();
      if (list == null) return invocation.proceed();
      var event = new LinkedHashMap<String, String>();
      event.put("method", invocation.getMethod().getName());
      event.put(
          "transaction",
          String.valueOf(TransactionSynchronizationManager.isActualTransactionActive()));
      if (invocation.getTarget() instanceof StatementHandler) {
        if (invocation.getMethod().getName().equals("prepare"))
          physical((Connection) invocation.getArgs()[0], event);
        else event.put("batch", "true");
      } else if (invocation.getArgs() != null && invocation.getArgs().length > 0) {
        var statement = (MappedStatement) invocation.getArgs()[0];
        event.put("statement", statement.getId());
        event.put("command", statement.getSqlCommandType().name());
      }
      list.add(event);
      Object result = invocation.proceed();
      event.put("completed", "true");
      if (invocation.getMethod().getName().equals("update"))
        event.put("affected-rows", String.valueOf(result));
      if (invocation.getMethod().getName().equals("flushStatements")) flush(result, event);
      return result;
    }

    /** 只读原生处理器已经取得的连接身份与真实事务设置。 */
    private static void physical(Connection connection, Map<String, String> event)
        throws SQLException {
      event.put("jdbc-url", connection.getMetaData().getURL());
      event.put("auto-commit", String.valueOf(connection.getAutoCommit()));
      event.put("isolation", String.valueOf(connection.getTransactionIsolation()));
      event.put("backend", String.valueOf(connection.unwrap(PGConnection.class).getBackendPID()));
    }

    /** 原样记录驱动返回的刷新计数，不把受理或未知计数解释为成功。 */
    private static void flush(Object result, Map<String, String> event) {
      List<String> counts = new ArrayList<>();
      for (Object item : (List<?>) result) {
        for (int count : ((BatchResult) item).getUpdateCounts()) counts.add(String.valueOf(count));
      }
      if (!counts.isEmpty()) event.put("flush-counts", String.join(",", counts));
    }
  }
}
