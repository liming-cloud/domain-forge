import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** 只观察应用启动迁移与真实 PostgreSQL；不替应用执行迁移、修复历史或创建业务数据。 */
public final class FlywayInventory {
  /** 工具不提供可实例化的持久化服务。 */
  private FlywayInventory() {}

  /**
   * 启动实际应用并采集私有观察，原生失败保留类型化原因。
   *
   * @param args 应用、数据源、迁移 Bean、Schema、表、列及私有输出路径
   * @throws Exception 工具或观察失败时终止，不能冒充迁移反例
   */
  public static void main(String[] args) throws Exception {
    try (var stream = Files.newOutputStream(Path.of(args[6]))) {
      var output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("flyway-observation");
      observeApplication(args, output);
      output.writeEndElement();
      output.close();
    }
  }

  /**
   * 仅调整校准日志与非 Web 运行方式，不注入数据源、迁移策略或初始化器。
   *
   * @param args 独立应用及期望读取对象
   * @param output 私有原生观察
   * @throws Exception 观察工具自身失败时终止
   */
  private static void observeApplication(String[] args, XMLStreamWriter output) throws Exception {
    var application = new SpringApplication(Class.forName(args[0]));
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.setDefaultProperties(Map.of("logging.level.root", "OFF"));
    ConfigurableApplicationContext context;
    try {
      context = application.run();
    } catch (Exception failure) {
      output.writeAttribute("started", "false");
      failures(failure, output);
      return;
    }
    try (context) {
      output.writeAttribute("started", "true");
      output.writeAttribute(
          "boot", SpringApplication.class.getPackage().getImplementationVersion());
      output.writeAttribute(
          "initializers",
          String.valueOf(context.getBeansOfType(FlywayMigrationInitializer.class).size()));
      database(context.getBean(args[1], DataSource.class), args, output);
      if (context.containsBean(args[2])) {
        migrations(context.getBean(args[2], Flyway.class), output);
      }
    }
  }

  /**
   * 保存原生迁移状态与只读校验结果，不调用 migrate、repair 或 clean。
   *
   * @param flyway 应用实际装配的迁移 Bean
   * @param output 私有迁移观察
   * @throws Exception 原生读取或输出失败时终止
   */
  private static void migrations(Flyway flyway, XMLStreamWriter output) throws Exception {
    output.writeStartElement("flyway");
    try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
      output.writeAttribute("jdbc-url", connection.getMetaData().getURL());
      String[] schemas = flyway.getConfiguration().getSchemas();
      String schema = flyway.getConfiguration().getDefaultSchema();
      if (schema == null) {
        schema = schemas.length == 0 ? connection.getSchema() : schemas[0];
      }
      output.writeAttribute("schema", schema);
      output.writeAttribute("managed-schemas", String.join(",", schemas));
    }
    var validation = flyway.validateWithResult();
    output.writeAttribute("valid", String.valueOf(validation.validationSuccessful));
    var information = flyway.info();
    output.writeAttribute(
        "current",
        String.valueOf(information.current() == null ? null : information.current().getVersion()));
    for (var migration : information.all()) {
      output.writeEmptyElement("migration");
      output.writeAttribute("version", String.valueOf(migration.getVersion()));
      output.writeAttribute("script", migration.getScript());
      output.writeAttribute("state", migration.getState().name());
      output.writeAttribute("type", migration.getType().name());
      output.writeAttribute("checksum", String.valueOf(migration.getChecksum()));
      output.writeAttribute("physical", String.valueOf(migration.getPhysicalLocation()));
    }
    output.writeEndElement();
  }

  /**
   * 通过实际业务数据源读取数据库身份和声明的合成结果，不按历史表存在推定数据正确。
   *
   * @param source 应用实际业务数据源
   * @param args 已由输入检查限定的 Schema、表与列
   * @param output 只保存在私有目录的数据观察
   * @throws Exception 连接或观察失败时终止
   */
  private static void database(DataSource source, String[] args, XMLStreamWriter output)
      throws Exception {
    try (var connection = source.getConnection()) {
      var metadata = connection.getMetaData();
      output.writeStartElement("database");
      output.writeAttribute("jdbc-url", metadata.getURL());
      output.writeAttribute("product", metadata.getDatabaseProductName());
      output.writeAttribute("version", metadata.getDatabaseProductVersion());
      output.writeAttribute("driver", metadata.getDriverVersion());
      connection.setReadOnly(true);
      data(connection, args, output);
      output.writeEndElement();
    }
  }

  /**
   * 读取至多一百零一行以识别超范围，不将截断后的结果当成完整通过。
   *
   * @param connection 已置为只读的实际连接
   * @param args 已限定为普通标识符的查询对象
   * @param output 私有合成结果
   * @throws Exception SQL 或输出失败时保留真实原因
   */
  private static void data(java.sql.Connection connection, String[] args, XMLStreamWriter output)
      throws Exception {
    String columns = "\"" + args[5].replace(",", "\",\"") + "\"";
    String query = "SELECT " + columns + " FROM \"" + args[3] + "\".\"" + args[4] + "\" LIMIT 101";
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
        output.writeEmptyElement("column");
        output.writeAttribute("name", rows.getMetaData().getColumnName(column));
        output.writeAttribute("type", rows.getMetaData().getColumnTypeName(column));
        output.writeAttribute("nullable", String.valueOf(rows.getMetaData().isNullable(column)));
      }
      while (rows.next()) {
        output.writeStartElement("row");
        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
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
    } catch (SQLException failure) {
      output.writeEmptyElement("query-failure");
      output.writeAttribute("sqlstate", String.valueOf(failure.getSQLState()));
    }
  }

  /**
   * 只保留固定错误类型和原生代码，不复制 SQL、密码或异常消息。
   *
   * @param failure 实际启动失败
   * @param output 私有类型化诊断
   * @throws Exception 输出失败时终止
   */
  private static void failures(Throwable failure, XMLStreamWriter output) throws Exception {
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
      if (failure instanceof FlywayException migration) {
        output.writeEmptyElement("native-failure");
        output.writeAttribute("flyway-code", String.valueOf(migration.getErrorCode()));
      }
      if (failure instanceof SQLException sql) {
        output.writeEmptyElement("native-failure");
        output.writeAttribute("sqlstate", String.valueOf(sql.getSQLState()));
      }
    }
  }
}
