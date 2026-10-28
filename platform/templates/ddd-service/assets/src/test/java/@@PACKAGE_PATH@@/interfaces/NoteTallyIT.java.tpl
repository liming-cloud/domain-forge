package @@PACKAGE@@.interfaces;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpLogic;
import @@PACKAGE@@.bootstrap.@@APP_CLASS@@;
import @@PACKAGE@@.infrastructure.messaging.SourceNoteCreatedHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.domainforge.messaging.api.ReceivedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 消费示例：@@SOURCE@@ 的 NoteCreated 翻译为本服务的统计累加，并经只需登录的查询读取。
 *
 * <p>投递、去重与死信由消息组件负责（其集成测试在真实 RocketMQ 上验证）；这里直接在事务内调用处理者，验证翻译与读模型。
 * IAM 由本地 HTTP 替身按令牌应答当前主体。
 */
class NoteTallyIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, Long> ACCOUNTS = new ConcurrentHashMap<>();
    private static HttpServer iam;
    private static ConfigurableApplicationContext application;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        assertNotNull(System.getenv("POSTGRES_PORT"), "需要真实数据库验证环境");
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/access/current", NoteTallyIT::subject);
        iam.start();
        application = new SpringApplicationBuilder(@@APP_CLASS@@.class).run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--management.server.port=0",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT")
                        + "/domainforge?currentSchema=@@SCHEMA@@",
                "--spring.datasource.username=domainforge",
                "--spring.datasource.password=" + env("POSTGRES_PASSWORD"),
                "--spring.flyway.schemas=@@SCHEMA@@",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + env("REDIS_PORT"),
                "--spring.data.redis.password=" + env("REDIS_PASSWORD"),
                "--@@ARTIFACT@@.iam.url=http://127.0.0.1:" + iam.getAddress().getPort(),
                "--domainforge.messaging.consumer.enabled=false",
                "--domainforge.messaging.relay.enabled=false",
                "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + application.getBean(Environment.class).getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (iam != null) iam.stop(0);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少 " + name);
        return value;
    }

    private static void subject(HttpExchange exchange) throws IOException {
        String token = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")).replace("Bearer ", "");
        Long account = ACCOUNTS.get(token);
        byte[] bytes = (account == null ? "{\"code\":\"X\",\"message\":\"x\",\"data\":null}"
                : "{\"code\":\"SUCCESS\",\"message\":\"成功\",\"data\":{\"accountId\":\"" + account
                        + "\",\"permissions\":[],\"roles\":[]}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(account == null ? 401 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String user(long account) {
        String[] token = new String[1];
        StpLogic logic = application.getBean(StpLogic.class);
        SaTokenContextMockUtil.setMockContext(() -> {
            logic.login(account);
            token[0] = logic.getTokenValue();
        });
        ACCOUNTS.put(token[0], account);
        return token[0];
    }

    private static void deliver(String type, int version, long owner) {
        var handler = application.getBean(SourceNoteCreatedHandler.class);
        var event = new ReceivedEvent("evt-" + System.nanoTime(), type, version, "1",
                "{\"noteId\":\"1\",\"ownerId\":\"" + owner + "\"}", 1);
        application.getBean(TransactionOperations.class).executeWithoutResult(status -> handler.handle(event));
    }

    private static String tally(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/@@NAMESPACE@@/note-tally"))
                .header(SaSameUtil.SAME_TOKEN, SaSameUtil.getToken())
                .header("Authorization", "Bearer " + token).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    // 每个 NoteCreated 为创建者加一；其他账号不受影响；其他事件类型忽略。
    @Test
    void eventsBuildTheTally() throws Exception {
        deliver("NoteCreated", 1, 701);
        deliver("NoteCreated", 1, 701);
        deliver("NoteCreated", 1, 702);
        deliver("NoteArchived", 1, 701);
        assertEquals("{\"code\":\"SUCCESS\",\"message\":\"成功\",\"data\":2}", tally(user(701)));
        assertEquals("{\"code\":\"SUCCESS\",\"message\":\"成功\",\"data\":0}", tally(user(703)));
    }

    // 不认识的合同版本不能被当作已处理：抛出异常，由消息组件重试并最终进入死信。
    @Test
    void unknownContractVersionFails() {
        assertThrows(IllegalStateException.class, () -> deliver("NoteCreated", 2, 704));
    }
}
