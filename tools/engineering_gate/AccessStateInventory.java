import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** 独立原生启动观察器，只盘点真实装配，不补鉴权、账号、DAO 或事务。 */
public final class AccessStateInventory {
  /** 仅提供独立进程入口，不允许实例化观察器。 */
  private AccessStateInventory() {}

  /** 各应用在独立 JVM 中启动，框架全局状态不能串成虚假的多实例。 */
  public static void main(String[] arguments) throws Exception {
    SpringApplication application = new SpringApplication(Class.forName(arguments[0]));
    ConfigurableApplicationContext context =
        application.run("--server.address=127.0.0.1", "--server.port=0");
    Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    Element root = document.createElement("access-runtime");
    document.appendChild(root);
    root.setAttribute("pid", Long.toString(ProcessHandle.current().pid()));
    root.setAttribute(
        "port", Integer.toString(((WebServerApplicationContext) context).getWebServer().getPort()));
    root.setAttribute("project", context.getEnvironment().getRequiredProperty("gate.project"));
    root.setAttribute("application-source", location(Class.forName(arguments[0])));
    database(context, root);
    redis(context, root);
    routes(context, root);
    Path path = Path.of(arguments[1]);
    try (var stream = Files.newOutputStream(path)) {
      TransformerFactory.newInstance()
          .newTransformer()
          .transform(new DOMSource(document), new StreamResult(stream));
    }
  }

  /** 取得实际池连接的只读原生元数据，不替应用执行账号操作。 */
  private static void database(ConfigurableApplicationContext context, Element root)
      throws Exception {
    try (var connection = context.getBean(DataSource.class).getConnection()) {
      var metadata = connection.getMetaData();
      root.setAttribute("jdbc-url", metadata.getURL());
      root.setAttribute("database-version", metadata.getDatabaseProductVersion());
      root.setAttribute("driver-version", metadata.getDriverVersion());
    }
  }

  /** DAO、连接工厂、库号与读取配置全部来自本次应用装配。 */
  private static void redis(ConfigurableApplicationContext context, Element root) {
    var factory = context.getBean(LettuceConnectionFactory.class);
    var dao = SaManager.getSaTokenDao();
    root.setAttribute("dao", dao.getClass().getName());
    root.setAttribute("dao-source", location(dao.getClass()));
    root.setAttribute(
        "dao-bound",
        Boolean.toString(
            dao instanceof SaTokenDaoForRedisTemplate nativeDao
                && nativeDao.stringRedisTemplate.getConnectionFactory() == factory));
    root.setAttribute("redis-host", factory.getHostName());
    root.setAttribute("redis-port", Integer.toString(factory.getPort()));
    root.setAttribute("redis-database", Integer.toString(factory.getDatabase()));
    var config = SaManager.getConfig();
    root.setAttribute("token-name", config.getTokenName());
    root.setAttribute("token-prefix", config.getTokenPrefix());
    root.setAttribute("read-header", config.getIsReadHeader().toString());
    root.setAttribute("read-body", config.getIsReadBody().toString());
    root.setAttribute("read-cookie", config.getIsReadCookie().toString());
  }

  /** 固定合同路径以实际 MVC 注册为准，拒绝遗漏或替换为未编译来源。 */
  private static void routes(ConfigurableApplicationContext context, Element root) {
    var mapping = context.getBean(RequestMappingHandlerMapping.class);
    for (var entry : mapping.getHandlerMethods().entrySet()) {
      for (String path : entry.getKey().getPatternValues()) {
        if (path.startsWith("/gate/")) {
          for (var method : entry.getKey().getMethodsCondition().getMethods()) {
            Element item = root.getOwnerDocument().createElement("route");
            item.setAttribute("path", path);
            item.setAttribute("method", method.name());
            item.setAttribute("source", location(entry.getValue().getBeanType()));
            root.appendChild(item);
          }
        }
      }
    }
  }

  /** 真实类来源与同次编译及依赖路径绑定，不接受名称相同的替身。 */
  private static String location(Class<?> type) {
    return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
  }
}
