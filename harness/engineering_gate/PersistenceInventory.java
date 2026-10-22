import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.w3c.dom.Element;

/** 观察实际 Mapper、动态选库与事务后的数据库状态，不补数据源、路由或事务实现。 */
public final class PersistenceInventory {
  /** 校准观察器不是可实例化的产品服务。 */
  private PersistenceInventory() {}

  /**
   * 读取工具生成的独立调用描述，在单独进程中保存私有观察。
   *
   * @param args 私有调用 XML 与私有观察输出路径
   * @throws Exception 输入、工具或观察错误时终止
   */
  public static void main(String[] args) throws Exception {
    var parser = DocumentBuilderFactory.newInstance();
    parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Element contract =
        parser.newDocumentBuilder().parse(Path.of(args[0]).toFile()).getDocumentElement();
    try (var stream = Files.newOutputStream(Path.of(args[1]))) {
      var output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("persistence-observation");
      application(contract, output);
      output.writeEndElement();
      output.close();
    }
  }

  /**
   * 启动真实应用，只调整校准日志和非 Web 模式，不替换任何原生资源。
   *
   * @param contract 独立调用对象
   * @param output 私有观察
   * @throws Exception 观察器自身失败时终止
   */
  private static void application(Element contract, XMLStreamWriter output) throws Exception {
    var application = new SpringApplication(Class.forName(contract.getAttribute("application")));
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.setDefaultProperties(Map.of("logging.level.root", "OFF"));
    ConfigurableApplicationContext context;
    try {
      context = application.run();
    } catch (Exception failure) {
      output.writeAttribute("started", "false");
      output.writeAttribute("failure", failure.getClass().getName());
      return;
    }
    try (context) {
      output.writeAttribute("started", "true");
      runOperations(context, contract, output);
    }
  }

  /**
   * 核对实际装配并透明记录原生 Executor 调用，不改 SQL、返回值或路由上下文。
   *
   * @param context 被测应用容器
   * @param contract 原生资源名称与调用对象
   * @param output 私有观察
   * @throws Exception 资源观察或输出失败时终止
   */
  private static void runOperations(
      ConfigurableApplicationContext context, Element contract, XMLStreamWriter output)
      throws Exception {
    Object source = bean(context, contract.getAttribute("datasource"));
    Object factory = bean(context, contract.getAttribute("factory"));
    Object manager = bean(context, contract.getAttribute("manager"));
    output.writeStartElement("resources");
    output.writeAttribute(
        "supported",
        String.valueOf(
            source instanceof DynamicRoutingDataSource
                && factory instanceof SqlSessionFactory
                && manager instanceof DataSourceTransactionManager));
    if (!(source instanceof DynamicRoutingDataSource routing)
        || !(factory instanceof SqlSessionFactory sessions)
        || !(manager instanceof DataSourceTransactionManager transactions)) {
      output.writeEndElement();
      return;
    }
    resources(routing, sessions, transactions, output);
    output.writeEndElement();
    var trace = new StatementTrace();
    sessions.getConfiguration().addInterceptor(trace);
    var operations = contract.getElementsByTagName("operation");
    for (int index = 0; index < operations.getLength(); index++) {
      operation(context, (Element) operations.item(index), contract, trace, output);
    }
  }

  /**
   * 返回实际资源；缺失资源保留为原生装配缺口，不制造替代 Bean。
   *
   * @param context 被测容器
   * @param name 独立声明的 Bean 名称
   * @return 实际 Bean 或空值
   */
  private static Object bean(ConfigurableApplicationContext context, String name) {
    return context.containsBean(name) ? context.getBean(name) : null;
  }

  /**
   * 保存真实池、默认资源及会话与事务的绑定身份，不从注解推断执行结果。
   *
   * @param routing 实际动态数据源
   * @param factory 实际会话工厂
   * @param manager 实际本地事务管理器
   * @param output 私有装配观察
   * @throws Exception 原生连接或输出失败时终止
   */
  private static void resources(
      DynamicRoutingDataSource routing,
      SqlSessionFactory factory,
      DataSourceTransactionManager manager,
      XMLStreamWriter output)
      throws Exception {
    output.writeAttribute(
        "mybatis-plus", String.valueOf(factory.getConfiguration() instanceof MybatisConfiguration));
    output.writeAttribute(
        "factory-bound",
        String.valueOf(factory.getConfiguration().getEnvironment().getDataSource() == routing));
    output.writeAttribute("manager-bound", String.valueOf(manager.getDataSource() == routing));
    output.writeAttribute("lookup", String.valueOf(DynamicDataSourceContextHolder.peek()));
    pool("primary", routing.getDataSource(""), output);
    for (var entry : routing.getDataSources().entrySet()) {
      pool(entry.getKey(), entry.getValue(), output);
    }
  }

  /**
   * 从当前操作代理链匹配的事务 Advice 读取默认规则，不用容器中闲置 Bean 代证。
   *
   * @param target 实际操作 Bean
   * @param operation 本次实际方法
   * @return 固定原生全异常规则是否存在；其他元数据装配明确为未校准
   * @throws ReflectiveOperationException 工具元数据探针不存在时终止
   */
  private static String defaultRollback(Object target, String operation)
      throws ReflectiveOperationException {
    if (!(target instanceof Advised proxy)) {
      return AopUtils.isAopProxy(target) ? "unsupported" : "not-transactional";
    }
    Class<?> targetClass = AopUtils.getTargetClass(target);
    if (SpringProxy.class.isAssignableFrom(targetClass)) {
      return "unsupported";
    }
    Method method = targetClass.getMethod(operation);
    var matched = matchingTransactions(proxy, method, targetClass);
    if (matched == null || matched.size() > 1) {
      return "unsupported";
    }
    if (matched.isEmpty()) {
      return "not-transactional";
    }
    return defaultRules(matched.get(0), method, targetClass);
  }

  /**
   * 只解析实际匹配 Advice 的固定原生默认规则，不把来源子类当作已校准实现。
   *
   * @param interceptor 实际匹配的事务 Advice
   * @param method 当前目标方法
   * @param targetClass 当前目标类
   * @return 默认规则状态；来源或声明未校准时不报通过
   * @throws ReflectiveOperationException 工具元数据方法不存在时终止
   */
  private static String defaultRules(
      TransactionInterceptor interceptor, Method method, Class<?> targetClass)
      throws ReflectiveOperationException {
    var source = interceptor.getTransactionAttributeSource();
    if (!nativeSource(source)) {
      return "unsupported";
    }
    if (source.getTransactionAttribute(method, targetClass) == null) {
      return "not-transactional";
    }
    var attribute =
        source.getTransactionAttribute(
            RollbackPolicyProbe.class.getMethod("inspect"), RollbackPolicyProbe.class);
    return String.valueOf(
        attribute instanceof RuleBasedTransactionAttribute rules
            && rules
                .getRollbackRules()
                .equals(List.of(RollbackRuleAttribute.ROLLBACK_ON_ALL_EXCEPTIONS)));
  }

  /**
   * 按当前代理的实际类过滤及静态方法匹配选择事务 Advice，不修改代理链。
   *
   * @param proxy 实际被调用的代理
   * @param method 实际目标方法
   * @param targetClass 实际目标类
   * @return 匹配的事务 Advice；动态或引介匹配等未校准形式返回空引用
   */
  private static List<TransactionInterceptor> matchingTransactions(
      Advised proxy, Method method, Class<?> targetClass) {
    var matched = new ArrayList<TransactionInterceptor>();
    for (var advisor : proxy.getAdvisors()) {
      if (!(advisor.getAdvice() instanceof TransactionInterceptor interceptor)) {
        continue;
      }
      if (interceptor.getClass() != TransactionInterceptor.class) {
        return null;
      }
      var pointcut = nativePointcut(advisor, interceptor, proxy.isPreFiltered());
      if (pointcut == null) {
        return null;
      }
      if (!proxy.isPreFiltered() && !pointcut.getClassFilter().matches(targetClass)) {
        continue;
      }
      var matcher = pointcut.getMethodMatcher();
      if (matcher.matches(method, targetClass)) {
        matched.add(interceptor);
      }
    }
    return matched;
  }

  /**
   * 限定固定 Spring 原生静态切点，仅读取其来源绑定，不额外执行未知匹配回调。
   *
   * @param advisor 当前代理链上的 Advisor
   * @param interceptor 实际事务 Advice
   * @param preFiltered 原生代理是否已完成类过滤
   * @return 绑定到同一原生元数据源的切点；其他形式或反射不可用时返回空引用
   */
  private static Pointcut nativePointcut(
      Advisor advisor, TransactionInterceptor interceptor, boolean preFiltered) {
    if (!(advisor instanceof PointcutAdvisor candidate)) {
      return null;
    }
    var source = interceptor.getTransactionAttributeSource();
    if (!nativeSource(source)) {
      return null;
    }
    var pointcut = candidate.getPointcut();
    String kind = "org.springframework.transaction.interceptor.TransactionAttributeSourcePointcut";
    if (!pointcut.getClass().getName().equals(kind)
        || !preFiltered
            && !pointcut
                .getClassFilter()
                .getClass()
                .getName()
                .equals(kind + "$TransactionAttributeSourceClassFilter")) {
      return null;
    }
    try {
      var binding = pointcut.getClass().getDeclaredField("transactionAttributeSource");
      return binding.trySetAccessible() && binding.get(pointcut) == source ? pointcut : null;
    } catch (ReflectiveOperationException | SecurityException failure) {
      return null;
    }
  }

  /**
   * 只支持固定原生来源及默认解析器集合，不额外调用自定义解析策略。
   *
   * @param source 实际事务元数据源
   * @return 原生配置可核对时返回真；自定义或反射不可用时返回假
   */
  private static boolean nativeSource(TransactionAttributeSource source) {
    if (source == null || source.getClass() != AnnotationTransactionAttributeSource.class) {
      return false;
    }
    try {
      var field = AnnotationTransactionAttributeSource.class.getDeclaredField("annotationParsers");
      if (!field.trySetAccessible()) {
        return false;
      }
      var actual = (Set<?>) field.get(source);
      var defaults = (Set<?>) field.get(new AnnotationTransactionAttributeSource());
      var actualTypes = actual.stream().map(Object::getClass).collect(Collectors.toSet());
      return actual.size() == defaults.size()
          && actualTypes.equals(
              defaults.stream().map(Object::getClass).collect(Collectors.toSet()));
    } catch (ReflectiveOperationException | SecurityException failure) {
      return false;
    }
  }

  /** 只供实际原生元数据源解析无局部覆盖的事务声明，不实例化或调用。 */
  public static final class RollbackPolicyProbe {
    /** 元数据探针没有可执行的业务实例。 */
    private RollbackPolicyProbe() {}

    /** 仅提供无覆盖的声明，调用表示工具误用并立即失败。 */
    @Transactional
    public void inspect() {
      throw new UnsupportedOperationException("Metadata probe must not run");
    }
  }

  /**
   * 读取连接身份；不通过设置路由键来掩盖被测配置。
   *
   * @param name 原生资源名
   * @param source 原生连接池
   * @param output 私有输出
   * @throws Exception 连接或输出失败时终止
   */
  private static void pool(String name, DataSource source, XMLStreamWriter output)
      throws Exception {
    try (var connection = source.getConnection()) {
      output.writeEmptyElement("pool");
      output.writeAttribute("name", name);
      output.writeAttribute("jdbc-url", connection.getMetaData().getURL());
    }
  }

  /**
   * 调用真实代理并在调用返回后以独立连接核对提交事实，不使用测试事务包住被测行为。
   *
   * @param context 实际应用
   * @param operation 独立调用对象
   * @param contract 只读合成表定义
   * @param trace 当前原生 Mapper 调用记录
   * @param output 私有场景观察
   * @throws Exception 工具或输出失败时终止
   */
  private static void operation(
      ConfigurableApplicationContext context,
      Element operation,
      Element contract,
      StatementTrace trace,
      XMLStreamWriter output)
      throws Exception {
    output.writeStartElement("operation");
    output.writeAttribute("scenario", operation.getAttribute("scenario"));
    trace.events.clear();
    Throwable failed = null;
    try {
      Object target = context.getBean(operation.getAttribute("bean"));
      output.writeAttribute(
          "default-rollback", defaultRollback(target, operation.getAttribute("method")));
      Object result = target.getClass().getMethod(operation.getAttribute("method")).invoke(target);
      output.writeAttribute("result-null", String.valueOf(result == null));
      if (result != null) {
        output.writeAttribute("result", String.valueOf(result));
      }
    } catch (InvocationTargetException failure) {
      failed = failure.getCause();
      output.writeAttribute("exception", failure.getCause().getClass().getName());
    } catch (ReflectiveOperationException failure) {
      failed = failure;
      output.writeAttribute("exception", failure.getClass().getName());
    }
    output.writeAttribute("lookup-after", String.valueOf(DynamicDataSourceContextHolder.peek()));
    failures(failed, output);
    for (var event : trace.events) {
      output.writeEmptyElement("trace");
      for (var entry : event.entrySet()) {
        output.writeAttribute(entry.getKey(), entry.getValue());
      }
    }
    Element data = (Element) contract.getElementsByTagName("data").item(0);
    snapshot("master", data, output);
    snapshot("inventory", data, output);
    output.writeEndElement();
  }

  /**
   * 保存有界的原生异常类型链，不复制异常消息、SQL 或凭据。
   *
   * @param failure 实际失败，允许外层 MyBatis 异常包装
   * @param output 私有类型观察
   * @throws Exception 输出失败时终止
   */
  private static void failures(Throwable failure, XMLStreamWriter output) throws Exception {
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
      output.writeEmptyElement("failure");
      output.writeAttribute("class", failure.getClass().getName());
    }
  }

  /**
   * 从已验证的独立临时目标读取有限行，不按应用的错误池绑定推定状态。
   *
   * @param name 本轮私有连接别名
   * @param data 已限定为普通标识符的表与列
   * @param output 私有数据观察
   * @throws Exception 工具或输出失败时终止
   */
  private static void snapshot(String name, Element data, XMLStreamWriter output) throws Exception {
    String prefix = "DF_GATE_" + name.toUpperCase(java.util.Locale.ROOT) + "_";
    output.writeStartElement("snapshot");
    output.writeAttribute("name", name);
    try (var connection =
        DriverManager.getConnection(
            System.getenv(prefix + "URL"),
            System.getenv(prefix + "USER"),
            System.getenv(prefix + "PASSWORD"))) {
      connection.setReadOnly(true);
      String query =
          PersistenceQueries.snapshot(
              data.getAttribute("schema"),
              data.getAttribute("table"),
              data.getAttribute("columns"),
              false);
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery(query)) {
        while (rows.next()) {
          output.writeStartElement("row");
          for (int column = 1; column <= 2; column++) {
            output.writeStartElement("value");
            String value = rows.getString(column);
            output.writeAttribute("null", String.valueOf(value == null));
            if (value != null) {
              output.writeCharacters(value);
            }
            output.writeEndElement();
          }
          output.writeEndElement();
        }
      }
    } catch (SQLException failure) {
      output.writeEmptyElement("query-failure");
      output.writeAttribute("sqlstate", String.valueOf(failure.getSQLState()));
    }
    output.writeEndElement();
  }

  /** 原生 Executor 的透明只读观察，不改写语句、参数、返回值或异常。 */
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
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class})
  })
  private static final class StatementTrace implements Interceptor {
    private final List<Map<String, String>> events = new ArrayList<>();
    private final Deque<Map<String, String>> active = new ArrayDeque<>();

    /** 只保存本轮调用记录，不注册为应用 Bean。 */
    private StatementTrace() {}

    /**
     * 在实际原生执行点读取连接与事务身份，原封不动转交执行。
     *
     * @param invocation 原生 Mapper 执行调用
     * @return 原生结果
     * @throws Throwable 原生调用的原始失败，不替换为成功
     */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      if (invocation.getTarget() instanceof StatementHandler) {
        physical((Connection) invocation.getArgs()[0]);
        return invocation.proceed();
      }
      return executor(invocation);
    }

    /**
     * 记录 Mapper 尝试与原始结果；不主动打开连接，也不改变失败时机。
     *
     * @param invocation 实际 Executor 调用
     * @return 原始执行结果
     * @throws Throwable 原始失败
     */
    private Object executor(Invocation invocation) throws Throwable {
      var statement = (MappedStatement) invocation.getArgs()[0];
      var event = new LinkedHashMap<String, String>();
      event.put("statement", statement.getId());
      event.put("command", statement.getSqlCommandType().name());
      event.put("lookup", String.valueOf(DynamicDataSourceContextHolder.peek()));
      event.put(
          "transaction",
          String.valueOf(TransactionSynchronizationManager.isActualTransactionActive()));
      event.put("completed", "false");
      event.put("prepared", "0");
      events.add(event);
      active.push(event);
      try {
        Object result = invocation.proceed();
        event.put("completed", "true");
        return result;
      } finally {
        active.pop();
      }
    }

    /**
     * 只读取原生 StatementHandler 已经取得并传入的连接，不替应用选择数据源。
     *
     * @param connection 原生执行器传入的真实连接
     * @throws SQLException 元数据读取失败时原样终止
     */
    private void physical(Connection connection) throws SQLException {
      Map<String, String> event = active.peek();
      if (event == null) {
        event = new LinkedHashMap<>();
        event.put("completed", "false");
        event.put(
            "transaction",
            String.valueOf(TransactionSynchronizationManager.isActualTransactionActive()));
        events.add(event);
      }
      event.put(
          "prepared", String.valueOf(Integer.parseInt(event.getOrDefault("prepared", "0")) + 1));
      event.put("jdbc-url", connection.getMetaData().getURL());
      event.put("auto-commit", String.valueOf(connection.getAutoCommit()));
    }
  }
}
