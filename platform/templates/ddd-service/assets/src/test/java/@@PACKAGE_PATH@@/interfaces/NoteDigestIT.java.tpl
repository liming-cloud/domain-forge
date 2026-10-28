package @@PACKAGE@@.interfaces;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpLogic;
import @@PACKAGE@@.bootstrap.@@APP_CLASS@@;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨服务调用示例：本服务以当前用户身份调用 @@PEER@@ 的便笺查询。@@PEER@@ 与 IAM 由本地 HTTP 替身按令牌应答，
 * 验证用户令牌、Same-Token 与请求标识的传递，以及对方 401／403、不可用与超时的翻译。
 */
class NoteDigestIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> BEHAVIOUR = new ConcurrentHashMap<>();
    private static final Map<String, Long> ACCOUNTS = new ConcurrentHashMap<>();
    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();
    private static HttpServer peer;
    private static ConfigurableApplicationContext application;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        assertNotNull(System.getenv("POSTGRES_PORT"), "需要真实数据库验证环境");
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/api/notes", NoteDigestIT::answer);
        peer.createContext("/internal/access/current", NoteDigestIT::subject);
        peer.setExecutor(Executors.newCachedThreadPool());
        peer.start();
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
                "--@@ENV@@_PEER_URL=http://127.0.0.1:" + peer.getAddress().getPort(),
                "--@@ARTIFACT@@.iam.url=http://127.0.0.1:" + peer.getAddress().getPort(),
                "--spring.cloud.openfeign.client.config.peerNotes.read-timeout=500",
                "--domainforge.messaging.consumer.enabled=false",
                "--domainforge.messaging.relay.enabled=false",
                "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + application.getBean(Environment.class).getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (peer != null) peer.stop(0);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少 " + name);
        return value;
    }

    /**
     * @@PEER@@ 替身：按 Bearer 令牌预置的行为应答；记录收到的用户令牌、Same-Token 与请求标识。
     */
    private static void answer(HttpExchange exchange) throws IOException {
        var headers = exchange.getRequestHeaders();
        String token = String.valueOf(headers.getFirst("Authorization")).replace("Bearer ", "");
        SEEN.put("authorization", String.valueOf(headers.getFirst("Authorization")));
        SEEN.put("same-token", String.valueOf(headers.getFirst("SA-SAME-TOKEN")));
        SEEN.put("request-id", String.valueOf(headers.getFirst("X-Request-Id")));
        String behaviour = BEHAVIOUR.getOrDefault(token, "401");
        if (behaviour.equals("slow")) sleep(1500);
        int status = behaviour.equals("ok") || behaviour.equals("slow") ? 200 : Integer.parseInt(behaviour);
        String body = status == 200
                ? "{\"code\":\"SUCCESS\",\"message\":\"成功\",\"data\":[{\"id\":\"1\",\"title\":\"甲\",\"version\":1},"
                        + "{\"id\":\"2\",\"title\":\"乙\",\"version\":3}]}"
                : "{\"code\":\"X\",\"message\":\"x\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * IAM 替身：登录校验读取的当前主体，只认已登记的令牌。
     */
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 在共享会话 Redis 中建立原生登录，并登记替身对该令牌的行为。
     */
    private static String user(long account, String behaviour) {
        String[] token = new String[1];
        StpLogic logic = application.getBean(StpLogic.class);
        SaTokenContextMockUtil.setMockContext(() -> {
            logic.login(account);
            token[0] = logic.getTokenValue();
        });
        BEHAVIOUR.put(token[0], behaviour);
        ACCOUNTS.put(token[0], account);
        return token[0];
    }

    private static HttpResponse<String> digest(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/@@NAMESPACE@@/note-digest"))
                .header(SaSameUtil.SAME_TOKEN, SaSameUtil.getToken())
                .header("X-Request-Id", "digest-it-1");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    // 以同一用户身份调用：对方收到原令牌、当前 Same-Token 与同一请求标识，摘要来自对方的可见便笺。
    @Test
    void callsPeerAsCurrentUser() throws Exception {
        String token = user(501, "ok");
        var response = digest(token);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"count\":2"), response.body());
        assertTrue(response.body().contains("\"titles\":[\"甲\",\"乙\"]"), response.body());
        assertEquals("Bearer " + token, SEEN.get("authorization"));
        assertEquals(SaSameUtil.getToken(), SEEN.get("same-token"));
        assertEquals("digest-it-1", SEEN.get("request-id"));
    }

    // 对方的 401、403 保持语义；对方不可用与超时为 503，不当作空摘要；本服务未登录时不调用对方。
    @Test
    void peerFailuresKeepTheirMeaning() throws Exception {
        assertEquals(401, digest(user(502, "401")).statusCode());
        assertEquals(403, digest(user(503, "403")).statusCode());
        assertEquals(503, digest(user(504, "503")).statusCode());
        assertEquals(503, digest(user(505, "slow")).statusCode());
        SEEN.clear();
        assertEquals(401, digest(null).statusCode());
        assertTrue(SEEN.isEmpty(), "未登录时不应调用对方");
    }
}
