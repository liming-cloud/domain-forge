import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

/** 独立数据库观察的固定XML语句；仅允许已声明的普通标识符，不接收业务SQL。 */
final class PersistenceQueries {
  private static final Configuration QUERIES = load();

  /** 观察查询是固定工具能力，不创建可变实例。 */
  private PersistenceQueries() {}

  /** JDBC时间展示设置也来自同一固定资源。 */
  static String timezone() {
    return QUERIES.getMappedStatement("observer.timezone").getBoundSql(null).getSql();
  }

  /** 标识符在进入XML替换前逐个限定；行数上限只在固定XML中定义。 */
  static String snapshot(String schema, String table, String columns, boolean ordered) {
    String selected =
        "*".equals(columns)
            ? columns
            : Arrays.stream(columns.split(","))
                .map(PersistenceQueries::identifier)
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(","));
    String name = ordered ? "observer.adapterSnapshot" : "observer.snapshot";
    return QUERIES
        .getMappedStatement(name)
        .getBoundSql(
            Map.of("schema", identifier(schema), "table", identifier(table), "columns", selected))
        .getSql();
  }

  /** 标识符白名单不允许表达式、引号或分隔符进入资源替换。 */
  private static String identifier(String value) {
    if (!value.matches("[A-Za-z_][A-Za-z0-9_]*"))
      throw new IllegalArgumentException("invalid identifier");
    return value;
  }

  /** 使用实际MyBatis XML解析，资源缺失或无效直接失败。 */
  private static Configuration load() {
    var configuration = new Configuration();
    try (var input = PersistenceQueries.class.getResourceAsStream("/PersistenceQueries.xml")) {
      if (input == null) throw new IllegalStateException("missing observer XML");
      new XMLMapperBuilder(
              input, configuration, "PersistenceQueries.xml", configuration.getSqlFragments())
          .parse();
      return configuration;
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("observer XML unavailable", failure);
    }
  }
}
