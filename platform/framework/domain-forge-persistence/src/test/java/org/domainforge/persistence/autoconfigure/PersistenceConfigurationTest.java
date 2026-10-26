package org.domainforge.persistence.autoconfigure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证组件公开装配合同；真实 SQL、迁移和事务另由消费工程验证。
 */
class PersistenceConfigurationTest {
    /**
     * 使用真实Boot定制器和JDBC管理器装配；不连接数据库或代替SQL回归。
     */
    private static AnnotationConfigApplicationContext nativeContext(
            Map<String, Object> properties, Class<?>... hostConfigurations) {
        var context = new AnnotationConfigApplicationContext();
        context
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("verification", properties));
        var source = new DriverManagerDataSource();
        context.registerBean("dataSource", DataSource.class, () -> source);
        context.registerBean(
                "sqlSessionFactory",
                SqlSessionFactory.class,
                () ->
                        new org.apache.ibatis.session.SqlSessionFactoryBuilder()
                                .build(
                                        new org.apache.ibatis.session.Configuration(
                                                new org.apache.ibatis.mapping.Environment(
                                                        "verification",
                                                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                                                        source))));
        context.registerBean(
                "flyway", Flyway.class, () -> Flyway.configure().dataSource(source).load());
        if (hostConfigurations.length > 0) {
            context.register(hostConfigurations);
        }
        context.register(DomainForgePersistenceConfiguration.class, NativeConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    /**
     * 分页使用 PostgreSQL 且上限有限，避免未设置上限的默认插件。
     */
    @Test
    void paginationUsesPostgresAndFiniteLimit() {
        var interceptor = new DomainForgePersistenceConfiguration().domainForgePagination(20);
        var pagination = (PaginationInnerInterceptor) interceptor.getInterceptors().getFirst();
        assertEquals(DbType.POSTGRE_SQL, pagination.getDbType());
        assertEquals(20L, pagination.getMaxLimit());
    }

    /**
     * 非正数和过大上限必须拒绝，不关闭分页防护。
     */
    @Test
    void invalidPaginationLimitsFail() {
        var configuration = new DomainForgePersistenceConfiguration();
        assertThrows(IllegalArgumentException.class, () -> configuration.domainForgePagination(0));
        assertThrows(IllegalArgumentException.class, () -> configuration.domainForgePagination(1001));
    }

    /**
     * 缺少必要资源必须失败，不能把未接入数据库当作成功启用。
     */
    @Test
    void missingResourcesCannotBecomeReady() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(DomainForgePersistenceConfiguration.class);
            assertThrows(BeanCreationException.class, context::refresh);
        }
    }

    /**
     * 不声明默认绑定的多数据源不得根据 Bean 顺序静默选择。
     */
    @Test
    void multipleDataSourcesAreRejected() {
        try (var context = LocalResourceBindingTest.context()) {
            context.removeBeanDefinition("dataSource");
            context.registerBean("firstSource", DataSource.class, () -> new DriverManagerDataSource());
            context.registerBean("secondSource", DataSource.class, () -> new DriverManagerDataSource());
            assertThrows(BeanCreationException.class, context::refresh);
        }
    }

    /**
     * 在调用迁移之前拒绝独立连接，不依赖迁移后的就绪检查。
     */
    @Test
    void invalidMigrationResourcesAreRejectedBeforeMigration() {
        var source = new DriverManagerDataSource();
        var other = new DriverManagerDataSource();
        var flyway = Flyway.configure().dataSource(other).load();
        var strategy = new DomainForgePersistenceConfiguration().domainForgeMigrationStrategy(source);
        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
    }

    /**
     * 同源检查不再覆盖已由原生配置设置的事务等待。
     */
    @Test
    void resourceGuardPreservesNativeTransactionTimeout() {
        try (var context = nativeContext(Map.of(), ConfiguredHostManager.class)) {
            assertEquals(30, context.getBean(DataSourceTransactionManager.class).getDefaultTimeout());
        }
    }

    /**
     * 事务和会话名称相同也不能掩盖实际资源不同。
     */
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
                                source,
                                transaction,
                                session,
                                flyway,
                                new org.springframework.boot.transaction.autoconfigure.TransactionProperties(),
                                new org.springframework.core.env.StandardEnvironment());
        assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
    }

    /**
     * 非有限事务等待配置直接拒绝。
     */
    @Test
    void invalidTransactionTimeoutIsRejected() {
        for (String timeout : List.of("0s", "301s")) {
            assertThrows(
                    BeanCreationException.class,
                    () -> nativeContext(Map.of("spring.transaction.default-timeout", timeout)));
        }
    }

    /**
     * 非模板消费者不声明超时仍保留组件十秒默认。
     */
    @Test
    void nativeConfigurationKeepsDefaultWithoutTemplate() {
        try (var context = nativeContext(Map.of())) {
            assertEquals(10, context.getBean(DataSourceTransactionManager.class).getDefaultTimeout());
        }
    }

    /**
     * 宿主自行提供管理器使Boot工厂退让时，未设超时仍须得到十秒默认。
     */
    @Test
    void hostBeanManagerReceivesDefaultWithoutTemplate() {
        try (var context = nativeContext(Map.of(), HostManager.class)) {
            assertEquals(10, context.getBean(DataSourceTransactionManager.class).getDefaultTimeout());
        }
    }

    /**
     * 宿主管理器仍使用唯一原生属性，显式值及有限边界均须实际生效。
     */
    @Test
    void nativeTimeoutConfiguresHostBeanManager() {
        for (int seconds : List.of(1, 30, 300)) {
            try (var context =
                         nativeContext(
                                 Map.of("spring.transaction.default-timeout", seconds + "s"), HostManager.class)) {
                assertEquals(
                        seconds, context.getBean(DataSourceTransactionManager.class).getDefaultTimeout());
            }
        }
    }

    /**
     * Boot退让不豁免有限超时与旧配置迁移检查。
     */
    @Test
    void hostBeanManagerRejectsInvalidNativeTimeout() {
        for (String timeout : List.of("-1s", "0s", "500ms", "301s")) {
            assertThrows(
                    BeanCreationException.class,
                    () ->
                            nativeContext(
                                    Map.of("spring.transaction.default-timeout", timeout), HostManager.class),
                    timeout);
        }
        var error =
                assertThrows(
                        BeanCreationException.class,
                        () ->
                                nativeContext(
                                        Map.of("domainforge.persistence.transaction-timeout-seconds", "10"),
                                        HostManager.class));
        assertTrue(
                error.getMostSpecificCause().getMessage().contains("spring.transaction.default-timeout"));
    }

    /**
     * 原生绑定是唯一显式配置来源，不能被资源检查尾部覆盖。
     */
    @Test
    void explicitNativeTimeoutOverridesPlatformDefault() {
        try (var context = nativeContext(Map.of("spring.transaction.default-timeout", "30s"))) {
            assertEquals(30, context.getBean(DataSourceTransactionManager.class).getDefaultTimeout());
        }
    }

    /**
     * 支持范围沿原生Duration绑定校验，边界值可用但不开放无限等待。
     */
    @Test
    void nativeTimeoutRangeIsEnforced() {
        for (String value : List.of("-1s", "500ms")) {
            assertThrows(
                    BeanCreationException.class,
                    () -> nativeContext(Map.of("spring.transaction.default-timeout", value)),
                    value);
        }
        for (int seconds : List.of(1, 300)) {
            try (var context =
                         nativeContext(Map.of("spring.transaction.default-timeout", seconds + "s"))) {
                assertEquals(
                        seconds, context.getBean(DataSourceTransactionManager.class).getDefaultTimeout());
            }
        }
    }

    /**
     * 旧键不能静默忽略，也不以双键优先级掩盖迁移。
     */
    @Test
    void legacyTimeoutKeyIsRejectedWithMigrationHint() {
        for (var values :
                List.of(
                        Map.<String, Object>of("domainforge.persistence.transaction-timeout-seconds", "10"),
                        Map.<String, Object>of(
                                "domainforge.persistence.transaction-timeout-seconds",
                                "10",
                                "spring.transaction.default-timeout",
                                "30s"))) {
            var error = assertThrows(BeanCreationException.class, () -> nativeContext(values));
            assertTrue(
                    error.getMostSpecificCause().getMessage().contains("spring.transaction.default-timeout"));
        }
    }

    /**
     * 按Boot导入选择器的真实顺序启用事务配置，避免手动注册顺序改变条件判断。
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            TransactionManagerCustomizationAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class
    })
    static class NativeConfiguration {
    }

    /**
     * 代表宿主通过原生Bean替换自动创建，不手动调用定制器。
     */
    @Configuration(proxyBeanMethods = false)
    static class HostManager {
        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
    }

    /**
     * 代表宿主明确设置的原生管理器默认值。
     */
    @Configuration(proxyBeanMethods = false)
    static class ConfiguredHostManager {
        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            var transaction = new DataSourceTransactionManager(source);
            transaction.setDefaultTimeout(30);
            return transaction;
        }
    }
}
