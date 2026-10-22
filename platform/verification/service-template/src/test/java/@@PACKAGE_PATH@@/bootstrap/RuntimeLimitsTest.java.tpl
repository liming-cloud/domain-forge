package @@PACKAGE@@.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import @@PACKAGE@@.infrastructure.persistence.RecordMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/** 在真实资源上验证池等待、SQL 与事务超时，不能只检查配置文本。 */
class RuntimeLimitsTest {
  /** 占满十个连接后新请求在约三秒明确失败。 */
  @Test
  void poolAcquisitionIsBounded() throws Exception {
    try (var context =
        new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run()) {
      var source = context.getBean(DataSource.class);
      var held = new ArrayList<Connection>();
      try {
        for (int i = 0; i < 10; i++) {
          held.add(source.getConnection());
        }
        long start = System.nanoTime();
        assertThrows(SQLException.class, source::getConnection);
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
        assertTrue(elapsed >= 2.5 && elapsed < 5, "连接获取必须遵守三秒边界");
      } finally {
        for (var connection : held) {
          connection.close();
        }
      }
    }
  }

  /** 隔离连接读超时后，真实 MyBatis 语句仍在五秒取消。 */
  @Test
  void mapperStatementIsCancelledAtFiveSeconds() {
    try (var context =
        new SpringApplicationBuilder(Application.class)
            .web(WebApplicationType.NONE)
            .run("--spring.datasource.hikari.data-source-properties.socketTimeout=20")) {
      var mapper = context.getBean(RecordMapper.class);
      long start = System.nanoTime();
      var failure =
          assertThrows(
              org.springframework.dao.DataAccessException.class,
              mapper::waitBeyondStatementDeadline);
      double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
      assertTrue(elapsed >= 4.5 && elapsed < 8, "语句须在五秒取消，不依赖二十秒连接读超时");
      assertTrue(failure.getMostSpecificCause() instanceof SQLException);
      assertEquals("57014", ((SQLException) failure.getMostSpecificCause()).getSQLState());
    }
  }

  /** 默认事务在十秒后禁止继续写入，并回滚超时前写入。 */
  @Test
  void transactionDeadlineRollsBack() {
    try (var context =
        new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run()) {
      var jdbc = new DatabaseFixture(context);
      jdbc.clear();
      var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      long start = System.nanoTime();
      assertThrows(
          TransactionTimedOutException.class,
          () ->
              transaction.executeWithoutResult(
                  status -> {
                    jdbc.beforeDeadline();
                    waitPastDeadline();
                    jdbc.afterDeadline();
                  }));
      assertTrue((System.nanoTime() - start) / 1_000_000_000.0 < 13);
      assertEquals(0, jdbc.count());
    }
  }

  /** 仅用于反证事务等待上限，生产源码不包含测试等待。 */
  private void waitPastDeadline() {
    try {
      Thread.sleep(10_200);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }
}
