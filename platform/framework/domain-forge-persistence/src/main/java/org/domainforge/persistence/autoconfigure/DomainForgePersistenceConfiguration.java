package org.domainforge.persistence.autoconfigure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.domainforge.persistence.api.AdditionalDataSource;
import org.domainforge.persistence.internal.TransactionBoundaryGuard;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.transaction.autoconfigure.TransactionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.RollbackOn;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * 由服务显式导入的主数据源装配：事务、Mapper 与迁移须绑定同一资源，不扫描业务包。附加数据源见
 * {@link AdditionalDataSource}。
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
public class DomainForgePersistenceConfiguration {
    /**
     * 只补齐绑定管理器的有限超时，不改变其他事务资源。
     */
    private static void configureTimeout(
            DataSourceTransactionManager transaction,
            TransactionProperties properties,
            Environment environment) {
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
        if (transaction.getDefaultTimeout() == TransactionDefinition.TIMEOUT_DEFAULT) {
            transaction.setDefaultTimeout(timeout == null ? 10 : Math.toIntExact(timeout.getSeconds()));
        }
    }

    /**
     * 设置 PostgreSQL 分页上限；服务仍须校验查询输入和任意自定义 SQL。
     */
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

    /**
     * 迁移前核对明确绑定的数据源，防止失败启动已改动错误数据库。
     */
    @Bean
    FlywayMigrationStrategy domainForgeMigrationStrategy(@Qualifier("dataSource") DataSource source) {
        return flyway -> {
            if (flyway.getConfiguration().getDataSource() != source) {
                throw new IllegalStateException("Flyway 必须使用明确绑定的 dataSource");
            }
            flyway.migrate();
        };
    }

    /**
     * 原生默认 Bean 名即绑定合同；自定义名称可声明别名，不按类型或顺序回落。
     */
    @Bean
    SmartInitializingSingleton domainForgePersistenceResources(
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("sqlSessionFactory") SqlSessionFactory sessions,
            @Qualifier("flyway") Flyway migration,
            ObjectProvider<AdditionalDataSource> additional,
            TransactionProperties properties,
            Environment environment) {
        if (!(manager instanceof DataSourceTransactionManager transaction)) {
            throw new IllegalStateException("transactionManager 必须管理绑定的本地 JDBC 资源");
        }
        configureTimeout(transaction, properties, environment);
        return () -> {
            if (transaction.getDataSource() != source
                    || sessions.getConfiguration().getEnvironment().getDataSource() != source
                    || migration.getConfiguration().getDataSource() != source) {
                throw new IllegalStateException("绑定的本地事务、会话与迁移必须使用同一数据源");
            }
            // 声明了附加数据源时，主数据源同样拒绝在其他数据源的事务中执行（双向防误用）。
            if (additional.stream().findAny().isPresent())
                sessions.getConfiguration().addInterceptor(new TransactionBoundaryGuard("dataSource", source));
        };
    }
}
