package org.domainforge.persistence.autoconfigure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.RollbackOn;

/** 由服务显式导入的单库装配：事务、Mapper 与迁移须绑定同一资源，不扫描业务包。 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
public class DomainForgePersistenceConfiguration {
  /** 设置 PostgreSQL 分页上限；服务仍须校验查询输入和任意自定义 SQL。 */
  @Bean
  MybatisPlusInterceptor domainForgePagination(
      @Value("${domainforge.persistence.max-page-size:200}") long maximum) {
    if (maximum < 1 || maximum > 1000) {
      throw new IllegalArgumentException("持久化分页上限须在 1 至 1000 之间");
    }
    PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
    pagination.setMaxLimit(maximum);
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(pagination);
    return interceptor;
  }

  /** 迁移前拒绝多数据源或独立 Flyway 连接，防止失败启动已改动错误数据库。 */
  @Bean
  FlywayMigrationStrategy domainForgeMigrationStrategy(List<DataSource> sources) {
    return flyway -> {
      if (sources.size() != 1 || flyway.getConfiguration().getDataSource() != sources.getFirst()) {
        throw new IllegalStateException("迁移前要求唯一数据源且 Flyway 使用同一资源");
      }
      flyway.migrate();
    };
  }

  /** 在服务就绪前核对单库资源，并设置应用事务默认等待上限。 */
  @Bean
  SmartInitializingSingleton domainForgePersistenceResources(
      List<DataSource> sources,
      List<PlatformTransactionManager> transactions,
      List<SqlSessionFactory> sessions,
      List<Flyway> migrations,
      @Value("${domainforge.persistence.transaction-timeout-seconds:10}") int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > 300) {
      throw new IllegalArgumentException("事务超时须在 1 至 300 秒之间");
    }
    if (sources.size() != 1
        || transactions.size() != 1
        || sessions.size() != 1
        || migrations.size() != 1) {
      throw new IllegalStateException("单库接入要求唯一数据源、事务管理器、会话工厂与迁移器");
    }
    return () -> {
      if (!(transactions.getFirst() instanceof DataSourceTransactionManager transaction)
          || transaction.getDataSource() != sources.getFirst()
          || sessions.getFirst().getConfiguration().getEnvironment().getDataSource()
              != sources.getFirst()
          || migrations.getFirst().getConfiguration().getDataSource() != sources.getFirst()) {
        throw new IllegalStateException("单库事务、会话与迁移必须使用同一数据源");
      }
      transaction.setDefaultTimeout(timeoutSeconds);
    };
  }
}
