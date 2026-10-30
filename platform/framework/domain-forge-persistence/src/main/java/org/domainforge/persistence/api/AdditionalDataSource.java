package org.domainforge.persistence.api;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.domainforge.persistence.internal.TransactionBoundaryGuard;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 服务的附加数据源（与主数据源不同的独立数据库）：连接池、版本化迁移、本地事务管理器与 MyBatis 会话工厂各自独立。
 *
 * <p>配置 {@code domainforge.datasources.<名称>.url／username／password}（凭据只来自环境变量）与可选的
 * {@code maximum-pool-size}（默认 5）；迁移目录 {@code classpath:db/<名称>}，Mapper XML 在
 * {@code classpath*:mapper-<名称>/**}{@code /*.xml}（与主数据源的 {@code mapper/} 目录分开）。创建时即执行迁移，失败则启动失败。
 *
 * <p>服务在 bootstrap 中把事务管理器与会话工厂声明为 {@code @Bean(defaultCandidate = false)}，主数据源的自动装配不受影响；
 * 用例以 {@code @Transactional("<名称>TransactionManager")} 选择事务。不提供跨数据源事务（P-14），
 * 在一个数据源的事务里访问另一个数据源的 Mapper 会被拒绝。
 */
public final class AdditionalDataSource implements AutoCloseable {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]{1,30}");
    private final String name;
    private final HikariDataSource dataSource;
    private final DataSourceTransactionManager transactionManager;
    private final SqlSessionFactory sqlSessionFactory;

    private AdditionalDataSource(String name, HikariDataSource dataSource, DataSourceTransactionManager manager,
                                 SqlSessionFactory sessions) {
        this.name = name;
        this.dataSource = dataSource;
        this.transactionManager = manager;
        this.sqlSessionFactory = sessions;
    }

    /**
     * 读取配置、建立连接池、执行迁移并组装事务与会话；任一步失败即关闭连接池并抛出。
     *
     * @param name        数据源名称：小写字母开头，字母与数字，2—31 位；不能是 {@code dataSource}
     * @param environment 服务环境
     * @return 已迁移的附加数据源
     */
    public static AdditionalDataSource create(String name, Environment environment) {
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("附加数据源名称须为小写字母开头的 2—31 位字母与数字：" + name);
        HikariDataSource pool = pool(name, environment);
        try {
            Flyway.configure().dataSource(pool).locations("classpath:db/" + name)
                    .cleanDisabled(true).validateOnMigrate(true).load().migrate();
            var manager = new DataSourceTransactionManager(pool);
            Duration timeout = environment.getProperty("spring.transaction.default-timeout", Duration.class,
                    Duration.ofSeconds(10));
            manager.setDefaultTimeout(Math.toIntExact(timeout.toSeconds()));
            return new AdditionalDataSource(name, pool, manager, sessions(name, pool));
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
    }

    private static HikariDataSource pool(String name, Environment environment) {
        String prefix = "domainforge.datasources." + name + ".";
        var config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(required(environment, prefix + "url"));
        config.setUsername(required(environment, prefix + "username"));
        config.setPassword(required(environment, prefix + "password"));
        config.setMaximumPoolSize(environment.getProperty(prefix + "maximum-pool-size", Integer.class, 5));
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(1000);
        config.addDataSourceProperty("connectTimeout", "3");
        config.addDataSourceProperty("socketTimeout", "5");
        return new HikariDataSource(config);
    }

    private static SqlSessionFactory sessions(String name, DataSource pool) {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);
        configuration.setDefaultStatementTimeout(5);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(pool);
        factory.setConfiguration(configuration);
        factory.setPlugins(pagination, new TransactionBoundaryGuard(name, pool));
        try {
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper-" + name + "/**/*.xml"));
            return factory.getObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (Exception failure) {
            throw new IllegalStateException("附加数据源 " + name + " 的会话工厂无法创建", failure);
        }
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        if (value.isBlank()) throw new IllegalStateException("附加数据源缺少配置 " + key);
        return value;
    }

    /**
     * 数据源名称。
     */
    public String name() {
        return name;
    }

    /**
     * 连接池。
     */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * 本数据源的本地事务管理器（默认超时同 {@code spring.transaction.default-timeout}）。
     */
    public DataSourceTransactionManager transactionManager() {
        return transactionManager;
    }

    /**
     * 本数据源的 MyBatis 会话工厂（含分页与跨数据源误用检查）。
     */
    public SqlSessionFactory sqlSessionFactory() {
        return sqlSessionFactory;
    }

    /**
     * 关闭连接池。
     */
    @Override
    public void close() {
        dataSource.close();
    }
}
