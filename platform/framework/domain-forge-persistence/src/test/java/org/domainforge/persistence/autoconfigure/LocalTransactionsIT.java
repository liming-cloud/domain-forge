package org.domainforge.persistence.autoconfigure;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.transaction.autoconfigure.TransactionProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 隔离 PostgreSQL 中验证实际代理和 MyBatis 事务，不使用测试方法自身事务。
 */
class LocalTransactionsIT {
    private AnnotationConfigApplicationContext context;
    private DataSource source;
    private String schema;
    private TransactionProbeMapper rows;

    private static DataSource source(String schema) {
        return new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:"
                        + required("POSTGRES_PORT")
                        + "/domainforge?currentSchema="
                        + schema,
                "domainforge",
                required("POSTGRES_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "先启动本次隔离 deploy 并提供 " + name);
        return value;
    }

    @BeforeEach
    void open() throws Exception {
        schema = "binding_" + UUID.randomUUID().toString().replace("-", "");
        source = source(schema);
        var migration =
                Flyway.configure()
                        .dataSource(source)
                        .schemas(schema)
                        .locations("classpath:local-binding")
                        .load();
        new DomainForgePersistenceConfiguration()
                .domainForgeMigrationStrategy(source)
                .migrate(migration);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/TransactionProbeMapper.xml"));
        factory.afterPropertiesSet();
        var sessions = factory.getObject();
        rows = new SqlSessionTemplate(sessions).getMapper(TransactionProbeMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, () -> source);
        context.registerBean(
                "transactionManager",
                DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(source));
        context.registerBean(
                "unrelatedManager",
                DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(new DriverManagerDataSource()));
        context.registerBean("sqlSessionFactory", SqlSessionFactory.class, () -> sessions);
        context.registerBean("flyway", Flyway.class, () -> migration);
        context.registerBean(TransactionProperties.class);
        context.registerBean(TransactionProbeMapper.class, () -> rows);
        context.register(DomainForgePersistenceConfiguration.class, Writes.class, JoinedWrite.class);
        context.refresh();
    }

    @AfterEach
    void close() throws Exception {
        if (context != null) context.close();
        if (source != null) {
            var resource = new ClassPathResource("local-binding-drop.sql");
            String script =
                    resource.getContentAsString(StandardCharsets.UTF_8).replace("${schema}", schema);
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Test
    void requiredJoinsRealTransactionAndCommitsThroughProxy() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var ids = context.getBean(Writes.class).write("committed", null);
        assertEquals(ids[0], ids[1], "REQUIRED 必须参与外层数据库事务");
        assertEquals(1, rows.count());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void checkedAndRuntimeExceptionsRollBackJoinedWrites() {
        var writes = context.getBean(Writes.class);
        assertThrows(Exception.class, () -> writes.write("checked", new Exception("checked")));
        assertEquals(0, rows.count());
        assertThrows(
                IllegalStateException.class,
                () -> writes.write("runtime", new IllegalStateException("runtime")));
        assertEquals(0, rows.count());
    }

    @Test
    void nativeShortTransactionCommitsAndRollsBack() {
        var transaction =
                new TransactionTemplate(
                        context.getBean("transactionManager", DataSourceTransactionManager.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        transaction.executeWithoutResult(
                status -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    rows.insert("short-commit");
                });
        assertEquals(1, rows.count());
        assertThrows(
                IllegalStateException.class,
                () ->
                        transaction.executeWithoutResult(
                                status -> {
                                    rows.insert("short-rollback");
                                    throw new IllegalStateException("rollback");
                                }));
        assertEquals(1, rows.count());
    }

    @Test
    void wrongMigrationIsRejectedBeforeAnyDatabaseWrite() {
        String otherSchema = schema + "_other";
        var other =
                Flyway.configure()
                        .dataSource(source(otherSchema))
                        .schemas(otherSchema)
                        .locations("classpath:local-binding")
                        .load();
        var strategy = new DomainForgePersistenceConfiguration().domainForgeMigrationStrategy(source);
        assertEquals(0, rows.schemaExists(otherSchema));
        assertThrows(IllegalStateException.class, () -> strategy.migrate(other));
        assertEquals(0, rows.schemaExists(otherSchema));
    }

    /**
     * 两个真实 Bean 的代理调用，不能用自调用代替 REQUIRED 的参与证明。
     */
    static class Writes {
        private final TransactionProbeMapper rows;
        private final JoinedWrite joined;

        Writes(TransactionProbeMapper rows, JoinedWrite joined) {
            this.rows = rows;
            this.joined = joined;
        }

        @Transactional(transactionManager = "transactionManager")
        public String[] write(String value, Exception failure) throws Exception {
            String outer = rows.transactionId();
            String inner = joined.insert(value);
            if (failure != null) throw failure;
            return new String[]{outer, inner};
        }
    }

    static class JoinedWrite {
        private final TransactionProbeMapper rows;

        JoinedWrite(TransactionProbeMapper rows) {
            this.rows = rows;
        }

        @Transactional(transactionManager = "transactionManager")
        public String insert(String value) {
            rows.insert(value);
            return rows.transactionId();
        }
    }
}
