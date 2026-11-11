package org.domainforge.messaging.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.domainforge.messaging.api.OutgoingEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL 上的中继租约：发送期间不持有行锁；租约过期后由其他实例接手，原实例迟到的结果不覆盖；
 * 同一顺序键在前一条被认领期间后续不可见；一批中途失败时释放未发送记录的租约。发送由假生产者控制，不连 RocketMQ。
 */
class OutboxLeaseIT {
    private static final String TOPIC = "df-it-lease";
    private static String schema;
    private static ConfigurableApplicationContext application;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        schema = "lease_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url(""), "domainforge", env("POSTGRES_PASSWORD")))
                .execute("CREATE SCHEMA " + schema);
        try (var connection = scoped().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("domain-forge/messaging/schema.sql"));
        }
        application = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.datasource.url=" + url(schema),
                "--spring.datasource.username=domainforge",
                "--spring.datasource.password=" + env("POSTGRES_PASSWORD"),
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.locations=classpath:messaging-it",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.baseline-version=0",
                "--domainforge.messaging.outbox.enabled=true",
                "--domainforge.messaging.relay.enabled=false");
        jdbc = application.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM domainforge_outbox");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "需要真实 PostgreSQL 验证环境：缺少 " + name);
        return value;
    }

    private static String url(String schema) {
        return "jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT") + "/domainforge"
                + (schema.isEmpty() ? "" : "?currentSchema=" + schema);
    }

    private static DriverManagerDataSource scoped() {
        return new DriverManagerDataSource(url(schema), "domainforge", env("POSTGRES_PASSWORD"));
    }

    // 发送被卡住时，中继不持有该行的锁：另一个连接能立即 FOR UPDATE NOWAIT 锁住它。
    @Test
    void sendingHoldsNoRowLock() throws Exception {
        long id = insert(OutgoingEvent.unordered(TOPIC, "Noted", 1, "k", "{}"));
        var gate = new Gate();
        var running = CompletableFuture.supplyAsync(relay(gate.producer())::relayOnce);
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "中继开始发送");
        try {
            assertTrue(lockable(id), "发送阻塞期间另一个连接能锁住该行");
        } finally {
            gate.pass();
        }
        assertEquals(1, running.get(10, TimeUnit.SECONDS));
        assertNotNull(jdbc.queryForObject("SELECT sent_at FROM domainforge_outbox WHERE id = ?", Object.class, id));
    }

    // 租约过期后另一实例重新认领并发送；原实例随后的失败结果只作用于自己的租约，不覆盖新结果。
    @Test
    void expiredLeaseIsReclaimedAndLateResultIsIgnored() throws Exception {
        long id = insert(OutgoingEvent.unordered(TOPIC, "Noted", 1, "k", "{}"));
        var stale = new Gate();
        var running = CompletableFuture.supplyAsync(relay(stale.producer())::relayOnce);
        assertTrue(stale.entered.await(10, TimeUnit.SECONDS), "原实例开始发送");
        jdbc.update("UPDATE domainforge_outbox SET claimed_until = now() - interval '1 second' WHERE id = ?", id);
        assertEquals(1, relay(producer(message -> null)).relayOnce(), "另一实例接手发送");
        var taken = jdbc.queryForMap("SELECT sent_at, claim_token FROM domainforge_outbox WHERE id = ?", id);
        stale.fail();
        assertEquals(0, running.get(10, TimeUnit.SECONDS));
        var row = jdbc.queryForMap(
                "SELECT sent_at, claim_token, attempts, last_error FROM domainforge_outbox WHERE id = ?",
                id);
        assertEquals(taken.get("sent_at"), row.get("sent_at"));
        assertEquals(taken.get("claim_token"), row.get("claim_token"));
        assertEquals(0, row.get("attempts"));
        assertNull(row.get("last_error"));
    }

    // 同一顺序键：前一条被认领、尚未发送时，其他实例看不到后续记录；前一条发送后才轮到后续。
    @Test
    void orderKeyStaysSerialWhileClaimed() throws Exception {
        insert(new OutgoingEvent(TOPIC, "Noted", 1, "k", "order", "{\"n\":1}"));
        long second = insert(new OutgoingEvent(TOPIC, "Noted", 1, "k", "order", "{\"n\":2}"));
        var gate = new Gate();
        var running = CompletableFuture.supplyAsync(relay(gate.producer())::relayOnce);
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "第一条开始发送");
        try {
            assertEquals(0, relay(producer(message -> null)).relayOnce(), "第一条未发送时第二条不可见");
        } finally {
            gate.pass();
        }
        assertEquals(1, running.get(10, TimeUnit.SECONDS));
        assertEquals(1, relay(producer(message -> null)).relayOnce());
        assertNotNull(jdbc.queryForObject("SELECT sent_at FROM domainforge_outbox WHERE id = ?", Object.class,
                second));
    }

    // 一批中途失败：失败的记录按退避重试，同批未发送的记录释放租约，下一轮即可发送。
    @Test
    void failureReleasesTheRestOfTheBatch() {
        long failing = insert(OutgoingEvent.unordered(TOPIC, "Noted", 1, "a", "{}"));
        long rest = insert(OutgoingEvent.unordered(TOPIC, "Noted", 1, "b", "{}"));
        assertEquals(0, relay(producer(message -> {
            throw new IllegalStateException("发送失败");
        })).relayOnce());
        var failed = jdbc.queryForMap("SELECT attempts, claim_token FROM domainforge_outbox WHERE id = ?", failing);
        assertEquals(1, failed.get("attempts"));
        assertNull(failed.get("claim_token"));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM domainforge_outbox WHERE id = ?", String.class, rest));
        assertEquals(1, relay(producer(message -> null)).relayOnce(), "未发送的记录下一轮即可发送");
    }

    private long insert(OutgoingEvent event) {
        String eventId = UUID.randomUUID().toString();
        application.getBean(TransactionOperations.class)
                .executeWithoutResult(status -> application.getBean(OutboxMapper.class).insert(event, eventId, null));
        return jdbc.queryForObject("SELECT id FROM domainforge_outbox WHERE event_id = ?", Long.class, eventId);
    }

    private static boolean lockable(long id) throws SQLException {
        try (var connection = scoped().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM domainforge_outbox WHERE id = ? FOR UPDATE NOWAIT")) {
                statement.setLong(1, id);
                statement.executeQuery().close();
                return true;
            } catch (SQLException locked) {
                if ("55P03".equals(locked.getSQLState())) return false;
                throw locked;
            } finally {
                connection.rollback();
            }
        }
    }

    private static OutboxRelay relay(Producer producer) {
        return new OutboxRelay(application.getBean(OutboxMapper.class), null,
                application.getBean(TransactionOperations.class), producer,
                new OutboxRelay.Settings(Duration.ofHours(1), 10, 24, true, false), new SimpleMeterRegistry(),
                new TraceContexts(Tracer.NOOP, Propagator.NOOP));
    }

    private static Producer producer(Send send) {
        return (Producer) Proxy.newProxyInstance(Producer.class.getClassLoader(), new Class<?>[]{Producer.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("send") && arguments.length == 1)
                        return send.apply((Message) arguments[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * 假生产者的一次发送。
     */
    private interface Send {
        Object apply(Message message) throws Exception;
    }

    /**
     * 把发送卡住，直到测试放行（成功）或判定失败。
     */
    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean failing;

        Producer producer() {
            return OutboxLeaseIT.producer(message -> {
                entered.countDown();
                assertTrue(released.await(30, TimeUnit.SECONDS), "测试放行发送");
                if (failing) throw new IllegalStateException("发送失败");
                return null;
            });
        }

        void pass() {
            released.countDown();
        }

        void fail() {
            failing = true;
            released.countDown();
        }
    }

    /**
     * 自动配置＋持久化组件；不启用中继、没有处理者，不连接 RocketMQ。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }
}
