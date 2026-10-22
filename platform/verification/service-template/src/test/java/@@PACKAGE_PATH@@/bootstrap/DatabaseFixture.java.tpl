package @@PACKAGE@@.bootstrap;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 从测试XML读取固定语句，保留原生JDBC超时异常与独立数据库观察。 */
final class DatabaseFixture {
  private final JdbcTemplate jdbc;
  private final SqlSessionFactory factory;

  /** 复用本测试上下文数据源，不调用被测业务查询自证。 */
  DatabaseFixture(ApplicationContext context) {
    jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    factory = context.getBean(SqlSessionFactory.class);
  }

  /** 清空当前独立用例的数据。 */
  void clear() {
    jdbc.update(statement("clear"));
  }

  /** 从真实数据库读取行数。 */
  int count() {
    return jdbc.queryForObject(statement("count"), Integer.class);
  }

  /** 核对首个迁移实际提交。 */
  int migrations() {
    return jdbc.queryForObject(statement("migrations"), Integer.class);
  }

  /** 在截止前写入，随后须随事务一起回滚。 */
  void beforeDeadline() {
    jdbc.update(statement("beforeDeadline"));
  }

  /** 原生JDBC在已超时事务继续写入须拒绝。 */
  void afterDeadline() {
    jdbc.update(statement("afterDeadline"));
  }

  private String statement(String name) {
    return factory
        .getConfiguration()
        .getMappedStatement("verification.database." + name)
        .getBoundSql(null)
        .getSql();
  }
}
