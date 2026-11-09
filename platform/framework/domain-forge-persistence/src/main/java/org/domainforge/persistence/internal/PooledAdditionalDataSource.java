package org.domainforge.persistence.internal;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.domainforge.persistence.api.AdditionalDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * {@link AdditionalDataSource} 的连接池实现；配置、命名与事务约定见该接口。
 */
public final class PooledAdditionalDataSource implements AdditionalDataSource {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]{1,30}");
    private final String name;
    private final HikariDataSource dataSource;
    private final DataSourceTransactionManager transactionManager;
    private final SqlSessionFactory sqlSessionFactory;

    private PooledAdditionalDataSource(String name, HikariDataSource dataSource, DataSourceTransactionManager manager,
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
    public static PooledAdditionalDataSource create(String name, Environment environment) {
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
            return new PooledAdditionalDataSource(name, pool, manager, sessions(name, pool));
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
    }

    private static HikariDataSource pool(String name, Environment environment) {
        String prefix = "domainforge.datasources." + name + ".";
        var settings = Binder.get(environment).bind(prefix.substring(0, prefix.length() - 1), Settings.class)
                .orElseGet(() -> new Settings("", "", "", 5));
        var config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(required(settings.url(), prefix + "url"));
        config.setUsername(required(settings.username(), prefix + "username"));
        config.setPassword(required(settings.password(), prefix + "password"));
        config.setMaximumPoolSize(settings.maximumPoolSize());
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
        // 与主数据源一致不打印 MyBatis-Plus 的文本 banner：部署环境的标准输出只有结构化日志。
        var global = GlobalConfigUtils.defaults();
        global.setBanner(false);
        factory.setGlobalConfig(global);
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

    /**
     * 附加数据源的连接配置（前缀 {@code domainforge.datasources.<名称>}）。
     */
    record Settings(@DefaultValue("") String url, @DefaultValue("") String username,
            @DefaultValue("") String password, @DefaultValue("5") int maximumPoolSize) {
    }

    private static String required(String value, String key) {
        if (value.isBlank()) throw new IllegalStateException("附加数据源缺少配置 " + key);
        return value;
    }

    /**
     * 数据源名称。
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 连接池。
     */
    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * 本数据源的本地事务管理器（默认超时同 {@code spring.transaction.default-timeout}）。
     */
    @Override
    public DataSourceTransactionManager transactionManager() {
        return transactionManager;
    }

    /**
     * 本数据源的 MyBatis 会话工厂（含分页与跨数据源误用检查）。
     */
    @Override
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
