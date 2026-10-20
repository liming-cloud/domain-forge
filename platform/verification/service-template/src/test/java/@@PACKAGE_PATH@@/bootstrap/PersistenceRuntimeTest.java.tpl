package @@PACKAGE@@.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import @@PACKAGE@@.application.command.WritePair;
import java.io.IOException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用发布组件、原生代理与真实 PostgreSQL 验证提交和回滚。 */
class PersistenceRuntimeTest {
  /** 成功用例提交两个值。 */
  @Test
  void successfulPairCommits() {
    verify("none", 2);
  }

  /** checked exception 必须回滚全部写入。 */
  @Test
  void checkedFailureRollsBackBothWrites() {
    verify("checked", 0);
  }

  /** 运行时异常必须回滚全部写入。 */
  @Test
  void runtimeFailureRollsBackBothWrites() {
    verify("runtime", 0);
  }

  /** 每次从相同固定迁移启动，再通过真实应用 Bean 调用。 */
  private void verify(String failure, int expected) {
    try (var context =
        new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run()) {
      var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
      jdbc.update("DELETE FROM probe_entry");
      var command = context.getBean(WritePair.class);
      if ("checked".equals(failure)) {
        assertThrows(IOException.class, () -> command.execute(1000, failure));
      } else if ("runtime".equals(failure)) {
        assertThrows(IllegalStateException.class, () -> command.execute(1000, failure));
      } else {
        assertDoesNotThrow(() -> command.execute(1000, failure));
      }
      assertEquals(
          expected, jdbc.queryForObject("SELECT count(*) FROM probe_entry", Integer.class));
      assertNotEquals(WritePair.class, command.getClass(), "必须经过事务代理");
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM flyway_schema_history WHERE success AND version='1'",
              Integer.class));
    }
  }
}
