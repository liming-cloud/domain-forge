package org.domainforge.persistence.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证组件公开装配合同；真实 SQL、迁移和事务另由消费工程验证。 */
class PersistenceConfigurationTest {
  /** 分页使用 PostgreSQL 且上限有限，避免未设置上限的默认插件。 */
  @Test
  void paginationUsesPostgresAndFiniteLimit() {
    var interceptor = new DomainForgePersistenceConfiguration().domainForgePagination(20);
    var pagination = (PaginationInnerInterceptor) interceptor.getInterceptors().getFirst();
    assertEquals(DbType.POSTGRE_SQL, pagination.getDbType());
    assertEquals(20L, pagination.getMaxLimit());
  }

  /** 非正数和过大上限必须拒绝，不关闭分页防护。 */
  @Test
  void invalidPaginationLimitsFail() {
    var configuration = new DomainForgePersistenceConfiguration();
    assertThrows(IllegalArgumentException.class, () -> configuration.domainForgePagination(0));
    assertThrows(IllegalArgumentException.class, () -> configuration.domainForgePagination(1001));
  }

  /** 缺少必要资源必须失败，不能把未接入数据库当作成功启用。 */
  @Test
  void missingResourcesCannotBecomeReady() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new DomainForgePersistenceConfiguration()
                .domainForgePersistenceResources(List.of(), List.of(), List.of(), List.of(), 10));
  }

  /** 多数据源不得根据 Bean 顺序静默选择。 */
  @Test
  void multipleDataSourcesAreRejected() {
    var source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
    assertThrows(
        IllegalStateException.class,
        () ->
            new DomainForgePersistenceConfiguration()
                .domainForgePersistenceResources(
                    List.of(source, source), List.of(), List.of(), List.of(), 10));
  }

  /** 在调用迁移之前拒绝多源及独立连接，不依赖迁移后的就绪检查。 */
  @Test
  void invalidMigrationResourcesAreRejectedBeforeMigration() {
    var source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
    var other = new org.springframework.jdbc.datasource.DriverManagerDataSource();
    var flyway = org.flywaydb.core.Flyway.configure().dataSource(other).load();
    var configuration = new DomainForgePersistenceConfiguration();
    for (var sources :
        List.of(
            List.<javax.sql.DataSource>of(source), List.<javax.sql.DataSource>of(source, other))) {
      var strategy = configuration.domainForgeMigrationStrategy(sources);
      assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
    }
  }

  /** 同一资源组合生效，超时在真实事务管理器上可读。 */
  @Test
  void singleResourceSetsNativeTransactionTimeout() {
    var source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
    var transaction = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
    var configuration =
        new org.apache.ibatis.session.Configuration(
            new org.apache.ibatis.mapping.Environment(
                "verification",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                source));
    var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
    var flyway = org.flywaydb.core.Flyway.configure().dataSource(source).load();
    new DomainForgePersistenceConfiguration()
        .domainForgePersistenceResources(
            List.of(source), List.of(transaction), List.of(session), List.of(flyway), 10)
        .afterSingletonsInstantiated();
    assertEquals(10, transaction.getDefaultTimeout());
  }

  /** 事务和会话名称相同也不能掩盖实际资源不同。 */
  @Test
  void differentTransactionResourceIsRejected() {
    var source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
    var other = new org.springframework.jdbc.datasource.DriverManagerDataSource();
    var transaction = new org.springframework.jdbc.datasource.DataSourceTransactionManager(other);
    var configuration =
        new org.apache.ibatis.session.Configuration(
            new org.apache.ibatis.mapping.Environment(
                "verification",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                source));
    var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
    var flyway = org.flywaydb.core.Flyway.configure().dataSource(source).load();
    var guard =
        new DomainForgePersistenceConfiguration()
            .domainForgePersistenceResources(
                List.of(source), List.of(transaction), List.of(session), List.of(flyway), 10);
    assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
  }

  /** 非有限事务等待配置直接拒绝。 */
  @Test
  void invalidTransactionTimeoutIsRejected() {
    for (int timeout : new int[] {0, 301}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DomainForgePersistenceConfiguration()
                  .domainForgePersistenceResources(
                      List.of(), List.of(), List.of(), List.of(), timeout));
    }
  }
}
