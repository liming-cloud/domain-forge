package org.domainforge.messaging.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.messaging.api.EventHandler;
import org.domainforge.messaging.api.EventPublisher;
import org.domainforge.messaging.api.OutgoingEvent;
import org.domainforge.messaging.api.ReceivedEvent;
import org.domainforge.persistence.autoconfigure.DomainForgePersistenceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL 与 RocketMQ（namesrv＋broker＋proxy）上的消息合同：同事务 Outbox、提交后发送、回滚不发送、
 * 应用中断与 RocketMQ 中断后继续发送、重复投递不重复生效、按顺序键有序、超过重试进入死信并告警。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessagingIT {
    private static final String NORMAL = "df-it-normal";
    private static final String FIFO = "df-it-fifo";
    private static final String NORMAL_GROUP = "df-it-normal-group";
    private static final String FIFO_GROUP = "df-it-fifo-group";
    private static final Map<String, AtomicInteger> HANDLED = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> ORDER = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> FLAKY = new ConcurrentHashMap<>();
    private static String schema;
    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void start() throws Exception {
        // proxy 就绪时 broker 可能尚未注册到 namesrv，此时按集群建主题会“成功”退出却什么也不建。
        await("broker 已注册", () -> output("clusterList").contains("broker-a"));
        admin("updateTopic", "-t", NORMAL, "-a", "+message.type=NORMAL");
        admin("updateTopic", "-t", FIFO, "-o", "true", "-a", "+message.type=FIFO");
        admin("updateSubGroup", "-g", NORMAL_GROUP, "-r", "2");
        admin("updateSubGroup", "-g", FIFO_GROUP, "-o", "true", "-r", "2");
        // broker 定期向 namesrv 上报路由，新建主题要等到能查到路由，客户端才能启动。
        for (String topic : List.of(NORMAL, FIFO)) await("主题路由可见：" + topic, () -> routed(topic));
        schema = "messaging_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(url(""), "domainforge", env("POSTGRES_PASSWORD"));
        new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
        var scoped = new DriverManagerDataSource(url(schema), "domainforge", env("POSTGRES_PASSWORD"));
        try (var connection = scoped.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("domain-forge/messaging/schema.sql"));
        }
        application = start(true);
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "需要真实 PostgreSQL 与 RocketMQ 验证环境：缺少 " + name);
        return value;
    }

    private static String url(String schema) {
        return "jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT") + "/domainforge"
                + (schema.isEmpty() ? "" : "?currentSchema=" + schema);
    }

    private static void admin(String command, String... arguments) throws Exception {
        var line = new ArrayList<>(List.of("docker", "exec", env("ROCKETMQ_CONTAINER"), "./mqadmin", command,
                "-n", "127.0.0.1:9876", "-c", "DefaultCluster"));
        line.addAll(List.of(arguments));
        var process = new ProcessBuilder(line).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("success"), output);
    }

    private static String output(String command, String... arguments) {
        try {
            var line = new ArrayList<>(List.of("docker", "exec", env("ROCKETMQ_CONTAINER"), "./mqadmin", command,
                    "-n", "127.0.0.1:9876"));
            line.addAll(List.of(arguments));
            var process = new ProcessBuilder(line).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            return process.waitFor() == 0 ? output : "";
        } catch (Exception unavailable) {
            return "";
        }
    }

    private static boolean routed(String topic) {
        return output("topicRoute", "-t", topic).contains("brokerDatas");
    }

    private static void docker(String command) throws Exception {
        var process = new ProcessBuilder("docker", command, env("ROCKETMQ_CONTAINER"))
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), "docker " + command + " 失败");
    }

    private static ConfigurableApplicationContext start(boolean relay) {
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.datasource.url=" + url(schema),
                "--spring.datasource.username=domainforge",
                "--spring.datasource.password=" + env("POSTGRES_PASSWORD"),
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.locations=classpath:messaging-it",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.baseline-version=0",
                "--domainforge.messaging.endpoints=" + env("ROCKETMQ_ENDPOINTS"),
                "--domainforge.messaging.outbox.enabled=true",
                "--domainforge.messaging.relay.enabled=" + relay,
                "--domainforge.messaging.relay.interval=PT0.5S",
                "--domainforge.messaging.consumer.max-attempts=3");
    }

    private static String publish(ConfigurableApplicationContext context, OutgoingEvent event) {
        return context.getBean(TransactionOperations.class)
                .execute(status -> context.getBean(EventPublisher.class).publish(event));
    }

    private static int effects(String eventId) {
        return application.getBean(JdbcTemplate.class)
                .queryForObject("SELECT count(*) FROM it_effect WHERE event_id = ?", Integer.class, eventId);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail("90 秒内未观察到：" + what);
    }

    private static double counter(String name, String group) {
        var found = application.getBean(MeterRegistry.class).find(name).tag("group", group).counter();
        return found == null ? 0 : found.count();
    }

    private static OutgoingEvent normal(String type, String key) {
        return OutgoingEvent.unordered(NORMAL, type, 1, key, "{\"key\":\"" + key + "\"}");
    }

    // 提交后发送：消费方收到同一事件 ID、类型、版本与业务键，处理效果与去重记录同事务提交。
    @Test
    @Order(1)
    void committedEventIsDelivered() throws Exception {
        String id = publish(application, normal("NoteCreated", "note-1"));
        await("事件被消费", () -> effects(id) == 1);
        var row = application.getBean(JdbcTemplate.class).queryForMap(
                "SELECT sent_at, attempts FROM domainforge_outbox WHERE event_id = ?", id);
        assertNotNull(row.get("sent_at"));
        assertEquals(0, row.get("attempts"));
        assertEquals("NoteCreated:1:note-1", NormalHandler.LAST.get(id));
    }

    // 链路贯穿 Outbox：消费方在发布时的同一条链路中处理（日志上下文的 traceId 与发布时一致）。
    @Test
    @Order(2)
    void consumerContinuesThePublishersTrace() throws Exception {
        var tracer = application.getBean(io.micrometer.tracing.Tracer.class);
        var span = tracer.nextSpan().name("publish-it").start();
        String id;
        try (var ignored = tracer.withSpan(span)) {
            id = publish(application, normal("NoteCreated", "traced"));
        } finally {
            span.end();
        }
        String eventId = id;
        await("事件被消费", () -> effects(eventId) == 1);
        assertEquals(span.context().traceId(), NormalHandler.TRACES.get(id));
    }

    // 业务回滚时事件一并消失；没有事务时不能发布。
    @Test
    @Order(3)
    void rolledBackOrMissingTransactionPublishesNothing() {
        var transactions = application.getBean(TransactionOperations.class);
        var publisher = application.getBean(EventPublisher.class);
        String[] id = new String[1];
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            id[0] = publisher.publish(normal("NoteCreated", "rolled-back"));
            throw new IllegalStateException("业务失败");
        }));
        assertEquals(0, application.getBean(JdbcTemplate.class)
                .queryForObject("SELECT count(*) FROM domainforge_outbox WHERE event_id = ?", Integer.class, id[0]));
        assertThrows(IllegalStateException.class, () -> publisher.publish(normal("NoteCreated", "no-tx")));
    }

    // 业务提交后、发送前应用中断：提交的事件留在 Outbox，由仍在运行或重启后的中继接手发送。
    @Test
    @Order(4)
    void committedEventSurvivesApplicationStop() throws Exception {
        String id;
        try (var stopped = start(false)) {
            id = publish(stopped, normal("NoteCreated", "before-crash"));
        }
        await("中断前提交的事件被接手发送并消费", () -> effects(id) == 1);
    }

    // RocketMQ 中断：发送失败记录次数并退避，恢复后发送；记录不丢弃。
    @Test
    @Order(5)
    void relayRetriesAfterBrokerOutage() throws Exception {
        docker("pause");
        String id;
        try {
            id = publish(application, normal("NoteCreated", "during-outage"));
            await("发送失败被记录", () -> application.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT attempts FROM domainforge_outbox WHERE event_id = ?", Integer.class, id) > 0);
        } finally {
            docker("unpause");
        }
        await("恢复后发送并消费", () -> effects(id) == 1);
    }

    // 记录发送结果前中断导致重发：同一事件 ID 再次投递，只确认不重复生效。
    @Test
    @Order(6)
    void redeliveryTakesEffectOnce() throws Exception {
        String id = publish(application, normal("NoteCreated", "resent"));
        await("首次消费", () -> effects(id) == 1);
        double duplicates = counter("domainforge.messaging.duplicate", NORMAL_GROUP);
        application.getBean(JdbcTemplate.class)
                .update("UPDATE domainforge_outbox SET sent_at = NULL WHERE event_id = ?", id);
        await("重发被识别为重复", () -> counter("domainforge.messaging.duplicate", NORMAL_GROUP) > duplicates);
        assertEquals(1, effects(id));
    }

    // 有顺序键：同一键按发布顺序处理，前一条失败重试时后面的不越过；不同键互不阻塞。
    @Test
    @Order(7)
    void orderedEventsKeepPerKeyOrder() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        FLAKY.put(run + "-a-2", new AtomicInteger());
        for (int index = 1; index <= 4; index++) {
            for (String key : List.of("a", "b")) {
                String value = run + "-" + key + "-" + index;
                publish(application, new OutgoingEvent(FIFO, "NoteEdited", 1, run + "-" + key, run + "-" + key,
                        "{\"value\":\"" + value + "\"}"));
            }
        }
        await("两个键都处理完", () -> ORDER.getOrDefault(run + "-a", List.of()).size() == 4
                && ORDER.getOrDefault(run + "-b", List.of()).size() == 4);
        for (String key : List.of("a", "b")) {
            assertEquals(List.of(run + "-" + key + "-1", run + "-" + key + "-2", run + "-" + key + "-3",
                    run + "-" + key + "-4"), ORDER.get(run + "-" + key));
        }
        assertTrue(FLAKY.get(run + "-a-2").get() >= 2, "失败的那条应被重试");
    }

    // 一直失败：超过最大投递次数后计入死信并告警，不被当作成功，也不写入效果。
    @Test
    @Order(8)
    void poisonMessageGoesToDeadLetter() throws Exception {
        double before = counter("domainforge.messaging.dead-letter", NORMAL_GROUP);
        String id = publish(application, normal("Poison", "poison"));
        await("进入死信", () -> counter("domainforge.messaging.dead-letter", NORMAL_GROUP) > before);
        assertEquals(0, effects(id));
    }

    /**
     * 自动配置＋持久化组件＋两个处理者。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(DomainForgePersistenceConfiguration.class)
    static class Application {
        @Bean
        NormalHandler normalHandler(JdbcTemplate jdbc) {
            return new NormalHandler(jdbc);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        FifoHandler fifoHandler() {
            return new FifoHandler();
        }
    }

    /**
     * 普通主题：在同一事务内写入效果表；Poison 类型总是失败。
     */
    static final class NormalHandler implements EventHandler {
        static final Map<String, String> LAST = new ConcurrentHashMap<>();
        static final Map<String, String> TRACES = new ConcurrentHashMap<>();
        private final JdbcTemplate jdbc;

        NormalHandler(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String topic() {
            return NORMAL;
        }

        @Override
        public String group() {
            return NORMAL_GROUP;
        }

        @Override
        public void handle(ReceivedEvent event) {
            if (event.type().equals("Poison")) throw new IllegalStateException("毒消息");
            jdbc.update("INSERT INTO it_effect (event_id, handled_by) VALUES (?, ?)", event.id(), NORMAL_GROUP);
            HANDLED.computeIfAbsent(event.id(), ignored -> new AtomicInteger()).incrementAndGet();
            LAST.put(event.id(), event.type() + ":" + event.version() + ":" + event.key());
            String traceId = org.slf4j.MDC.get("traceId");
            if (traceId != null) TRACES.put(event.id(), traceId);
        }
    }

    /**
     * 顺序主题：按键记录处理顺序；标记为不稳定的事件第一次处理失败。
     */
    static final class FifoHandler implements EventHandler {
        @Override
        public String topic() {
            return FIFO;
        }

        @Override
        public String group() {
            return FIFO_GROUP;
        }

        @Override
        public void handle(ReceivedEvent event) {
            String value = event.payload().replaceAll(".*\"value\":\"([^\"]+)\".*", "$1");
            AtomicInteger flaky = FLAKY.get(value);
            if (flaky != null && flaky.incrementAndGet() == 1) throw new IllegalStateException("第一次失败");
            ORDER.computeIfAbsent(event.key(), ignored -> new CopyOnWriteArrayList<>()).add(value);
        }
    }
}
