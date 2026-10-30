package org.domainforge.persistence.additional;

import org.apache.ibatis.session.SqlSessionFactory;
import org.domainforge.persistence.api.AdditionalDataSource;
import org.domainforge.persistence.api.AdditionalMapper;
import org.domainforge.persistence.autoconfigure.DomainForgePersistenceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL 中两个独立数据库：主数据源的自动装配不因附加数据源退让，两个库各自迁移、读写与回滚；
 * 在一个数据源的事务中访问另一个数据源的 Mapper 被拒绝（双向）；跨数据源没有原子性。
 */
class AdditionalDataSourceIT {
    private static String database;
    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void start() {
        database = "extra_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        admin().execute("CREATE DATABASE " + database);
        String schema = "primary_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        admin().execute("CREATE SCHEMA " + schema);
        application = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.datasource.url=" + url("domainforge") + "?currentSchema=" + schema,
                "--spring.datasource.username=domainforge",
                "--spring.datasource.password=" + env("POSTGRES_PASSWORD"),
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.locations=classpath:additional-it/primary",
                "--domainforge.datasources.extra.url=" + url(database),
                "--domainforge.datasources.extra.username=domainforge",
                "--domainforge.datasources.extra.password=" + env("POSTGRES_PASSWORD"));
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        admin().execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "需要真实 PostgreSQL 验证环境：缺少 " + name);
        return value;
    }

    private static String url(String name) {
        return "jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT") + "/" + name;
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(url("domainforge"), "domainforge",
                env("POSTGRES_PASSWORD")));
    }

    private static Writes writes() {
        return application.getBean(Writes.class);
    }

    private static String value() {
        return UUID.randomUUID().toString();
    }

    // 主数据源仍由 Spring Boot 自动装配（未因附加数据源退让）；附加数据源连到自己的库并已迁移。
    @Test
    void bothDataSourcesAreBound() {
        assertEquals("domainforge", application.getBean(JdbcTemplate.class)
                .queryForObject("SELECT current_database()", String.class));
        assertEquals(database, application.getBean(ExtraRowMapper.class).database());
        assertEquals(1, admin().queryForObject("SELECT count(*) FROM pg_database WHERE datname = ?", Integer.class,
                database));
    }

    // 两个库在各自的事务中提交与回滚，互不影响。
    @Test
    void eachDataSourceCommitsAndRollsBackOnItsOwn() {
        String committed = value();
        writes().primary(committed, false);
        writes().extra(committed, false);
        String failed = value();
        assertThrows(IllegalStateException.class, () -> writes().primary(failed, true));
        assertThrows(IllegalStateException.class, () -> writes().extra(failed, true));
        var primary = application.getBean(PrimaryRowMapper.class);
        var extra = application.getBean(ExtraRowMapper.class);
        assertEquals(1, primary.count(committed));
        assertEquals(1, extra.count(committed));
        assertEquals(0, primary.count(failed));
        assertEquals(0, extra.count(failed));
        assertEquals(0, primary.count("extra-only-" + committed));
    }

    // 在一个数据源的事务中访问另一个数据源的 Mapper 被拒绝（双向），不会静默地以自动提交执行。
    @Test
    void crossDataSourceMisuseIsRejected() {
        String inPrimary = value();
        var primaryFailure = assertThrows(RuntimeException.class, () -> writes().extraInsidePrimary(inPrimary));
        assertTrue(String.valueOf(rootMessage(primaryFailure)).contains("数据源 extra"), rootMessage(primaryFailure));
        String inExtra = value();
        var extraFailure = assertThrows(RuntimeException.class, () -> writes().primaryInsideExtra(inExtra));
        assertTrue(String.valueOf(rootMessage(extraFailure)).contains("数据源 dataSource"), rootMessage(extraFailure));
        assertEquals(0, application.getBean(ExtraRowMapper.class).count(inPrimary));
        assertEquals(0, application.getBean(PrimaryRowMapper.class).count(inExtra));
    }

    // 跨数据源没有原子性：附加库已提交后主库失败，附加库的结果保留；业务须按可重复执行设计（P-14）。
    @Test
    void crossResourceIsNotAtomic() {
        String value = value();
        writes().extra(value, false);
        assertThrows(IllegalStateException.class, () -> writes().primary(value, true));
        assertEquals(1, application.getBean(ExtraRowMapper.class).count(value));
        assertEquals(0, application.getBean(PrimaryRowMapper.class).count(value));
    }

    // 配置缺失或名称不合法时启动失败。
    @Test
    void invalidDeclarationFailsFast() {
        var environment = application.getBean(Environment.class);
        assertThrows(IllegalStateException.class, () -> AdditionalDataSource.create("missing", environment));
        assertThrows(IllegalArgumentException.class, () -> AdditionalDataSource.create("Bad-Name", environment));
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage();
    }

    /**
     * 用例：各自事务中的写入，以及两种误用。
     */
    static class Writes {
        private final PrimaryRowMapper primary;
        private final ExtraRowMapper extra;

        Writes(PrimaryRowMapper primary, ExtraRowMapper extra) {
            this.primary = primary;
            this.extra = extra;
        }

        @Transactional
        public void primary(String value, boolean fail) {
            primary.insert(value);
            if (fail) throw new IllegalStateException("主库失败");
        }

        @Transactional("extraTransactionManager")
        public void extra(String value, boolean fail) {
            extra.insert(value);
            if (fail) throw new IllegalStateException("附加库失败");
        }

        @Transactional
        public void extraInsidePrimary(String value) {
            extra.insert(value);
        }

        @Transactional("extraTransactionManager")
        public void primaryInsideExtra(String value) {
            primary.insert(value);
        }
    }

    /**
     * 服务 bootstrap 的写法：主数据源照常自动装配；附加数据源 extra 的事务与会话不是默认候选。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(DomainForgePersistenceConfiguration.class)
    @MapperScan(basePackageClasses = PrimaryRowMapper.class,
            annotationClass = org.apache.ibatis.annotations.Mapper.class)
    @MapperScan(basePackageClasses = ExtraRowMapper.class, annotationClass = AdditionalMapper.class,
            sqlSessionFactoryRef = "extraSqlSessionFactory")
    static class Application {
        @Bean(destroyMethod = "close")
        AdditionalDataSource extra(Environment environment) {
            return AdditionalDataSource.create("extra", environment);
        }

        @Bean(defaultCandidate = false)
        PlatformTransactionManager extraTransactionManager(AdditionalDataSource extra) {
            return extra.transactionManager();
        }

        @Bean(defaultCandidate = false)
        SqlSessionFactory extraSqlSessionFactory(AdditionalDataSource extra) {
            return extra.sqlSessionFactory();
        }

        @Bean
        Writes writes(PrimaryRowMapper primary, ExtraRowMapper extra) {
            return new Writes(primary, extra);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
