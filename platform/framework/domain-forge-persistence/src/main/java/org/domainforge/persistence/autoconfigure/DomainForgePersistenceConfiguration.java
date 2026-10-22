package org.domainforge.persistence.autoconfigure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizer;
import org.springframework.boot.transaction.autoconfigure.TransactionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.RollbackOn;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

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

  /** 沿用Boot唯一事务属性；仅管理器未设超时时由原生定制点补有限默认。 */
  @Bean
  TransactionManagerCustomizer<AbstractPlatformTransactionManager> domainForgeTransactionDefaults(
      TransactionProperties properties, Environment environment) {
    if (environment.containsProperty("domainforge.persistence.transaction-timeout-seconds")) {
      throw new IllegalArgumentException(
          "旧属性domainforge.persistence.transaction-timeout-seconds已移除，请迁移到spring.transaction.default-timeout");
    }
    Duration timeout = properties.getDefaultTimeout();
    if (timeout != null
        && (timeout.compareTo(Duration.ofSeconds(1)) < 0
            || timeout.compareTo(Duration.ofSeconds(300)) > 0)) {
      throw new IllegalArgumentException("spring.transaction.default-timeout须在1至300秒之间");
    }
    return transaction -> {
      if (transaction.getDefaultTimeout() == TransactionDefinition.TIMEOUT_DEFAULT) {
        transaction.setDefaultTimeout(timeout == null ? 10 : Math.toIntExact(timeout.getSeconds()));
      }
    };
  }

  /** 在服务就绪前核对单库资源，不覆盖宿主的原生事务配置。 */
  @Bean
  SmartInitializingSingleton domainForgePersistenceResources(
      List<DataSource> sources,
      List<PlatformTransactionManager> transactions,
      List<SqlSessionFactory> sessions,
      List<Flyway> migrations,
      @Qualifier("domainForgeTransactionDefaults")
          TransactionManagerCustomizer<AbstractPlatformTransactionManager> defaults) {
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
      if (transaction.getDefaultTimeout() == TransactionDefinition.TIMEOUT_DEFAULT) {
        defaults.customize(transaction);
      }
    };
  }
}
