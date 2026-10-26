package org.domainforge.persistence.autoconfigure;

import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用 Boot/Flyway/MyBatis-Plus 原生自动装配，覆盖手工 Bean 上下文以外的初始化顺序。
 */
class BootResourceBindingIT {
    private static Map<String, Object> properties(String schema) {
        var properties = new HashMap<String, Object>();
        properties.put("spring.datasource.url", source().getUrl() + "?currentSchema=" + schema);
        properties.put("spring.datasource.username", "domainforge");
        properties.put("spring.datasource.password", required("POSTGRES_PASSWORD"));
        properties.put("spring.flyway.schemas", schema);
        properties.put("spring.flyway.locations", "classpath:local-binding");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "OFF");
        return properties;
    }

    private static DriverManagerDataSource source() {
        return new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:" + required("POSTGRES_PORT") + "/domainforge",
                "domainforge",
                required("POSTGRES_PASSWORD"));
    }

    private static void drop(String schema) throws Exception {
        String sql =
                new ClassPathResource("local-binding-drop.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("${schema}", schema);
        try (var connection = source().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少本次隔离 deploy 环境 " + name);
        return value;
    }

    @Test
    void nativeBootConfigurationMigratesAndBindsSameSource() throws Exception {
        String schema = "boot_binding_" + UUID.randomUUID().toString().replace("-", "");
        try {
            try (var context =
                         new SpringApplicationBuilder(Application.class)
                                 .web(WebApplicationType.NONE)
                                 .properties(properties(schema))
                                 .run()) {
                var source = context.getBean("dataSource", javax.sql.DataSource.class);
                assertSame(
                        source, context.getBean("flyway", Flyway.class).getConfiguration().getDataSource());
                assertSame(
                        source,
                        context
                                .getBean("transactionManager", DataSourceTransactionManager.class)
                                .getDataSource());
                assertSame(
                        source,
                        context
                                .getBean("sqlSessionFactory", SqlSessionFactory.class)
                                .getConfiguration()
                                .getEnvironment()
                                .getDataSource());
                assertEquals("1", context.getBean(Flyway.class).info().current().getVersion().toString());
                assertEquals(0, context.getBean(Flyway.class).info().pending().length);
            }
        } finally {
            drop(schema);
        }
    }

    @Test
    void bootRejectsIndependentFlywayConnectionBeforeMigration() throws Exception {
        String schema = "wrong_binding_" + UUID.randomUUID().toString().replace("-", "");
        var properties = properties(schema);
        properties.put("spring.flyway.url", properties.get("spring.datasource.url"));
        properties.put("spring.flyway.user", "domainforge");
        properties.put("spring.flyway.password", required("POSTGRES_PASSWORD"));
        assertThrows(
                RuntimeException.class,
                () -> {
                    try (var ignored =
                                 new SpringApplicationBuilder(Application.class)
                                         .web(WebApplicationType.NONE)
                                         .properties(properties)
                                         .run()) {
                        fail("独立 Flyway 连接不得通过同资源核对");
                    }
                });
        try (var connection = source().getConnection();
             var schemas = connection.getMetaData().getSchemas(null, schema)) {
            assertFalse(schemas.next(), "拒绝启动前不能创建目标 schema");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(DomainForgePersistenceConfiguration.class)
    static class Application {
    }
}
