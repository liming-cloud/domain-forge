package @@PACKAGE@@.interfaces;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpLogic;
import @@PACKAGE@@.application.command.note.NotePermissions;
import @@PACKAGE@@.bootstrap.@@APP_CLASS@@;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.domainforge.access.api.SameTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 与会话 Redis 上的服务接入：IAM 由本地 HTTP 替身按公开合同应答，验证本服务的 Feign 适配、
 * 权限注解、数据范围过滤、业务拒绝与依赖故障。
 */
class NoteServiceIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> SUBJECTS = new ConcurrentHashMap<>();
    private static final Map<String, String> SCOPES = new ConcurrentHashMap<>();
    private static final AtomicBoolean IAM_DOWN = new AtomicBoolean();
    private static HttpServer iam;
    private static ConfigurableApplicationContext application;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        assertNotNull(System.getenv("POSTGRES_PORT"), "需要真实数据库验证环境");
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/access/", NoteServiceIT::answer);
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
                "--domainforge.messaging.relay.enabled=false",
                "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + application.getBean(Environment.class).getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
        if (iam != null) iam.stop(0);
    }

    @BeforeEach
    void reset() {
        application.getBean(JdbcTemplate.class).update("TRUNCATE note, domainforge_outbox");
        SUBJECTS.clear();
        SCOPES.clear();
        IAM_DOWN.set(false);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少 " + name);
        return value;
    }

    /**
     * IAM 替身：按 Bearer 令牌返回预置主体与范围；未知令牌或缺少有效 Same-Token 时 401，故障开关 503。
     */
    private static void answer(HttpExchange exchange) throws IOException {
        String token = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")).replace("Bearer ", "");
        boolean internal = SameTokens.current().equals(exchange.getRequestHeaders().getFirst(SameTokens.HEADER));
        String path = exchange.getRequestURI().getPath();
        String body = path.endsWith("/current") ? SUBJECTS.get(token) : SCOPES.get(token);
        int status = IAM_DOWN.get() ? 503 : body == null || !internal ? 401 : 200;
        byte[] bytes = (status == 200 ? "{\"code\":\"SUCCESS\",\"message\":\"成功\",\"data\":" + body + "}"
                : "{\"code\":\"X\",\"message\":\"x\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 在共享会话 Redis 中建立原生登录，并登记替身应答。
     */
    private static String user(long account, String permissions, String readScope, String writeScope) {
        String[] token = new String[1];
        StpLogic logic = application.getBean(StpLogic.class);
        SaTokenContextMockUtil.setMockContext(() -> {
            logic.login(account);
            token[0] = logic.getTokenValue();
        });
        SUBJECTS.put(token[0],
                "{\"accountId\":\"" + account + "\",\"permissions\":[" + permissions + "],\"roles\":[]}");
        SCOPES.put(token[0],
                "{\"" + READ + "\":" + readScope + ",\"" + WRITE + "\":" + writeScope + "}");
        return token[0];
    }

    private static String scope(boolean all, boolean self, String units) {
        return "{\"all\":" + all + ",\"self\":" + self + ",\"unitIds\":[" + units + "]}";
    }

    /**
     * 按网关转发的形态调用：携带当前 Same-Token。
     */
    private static HttpResponse<String> call(String method, String path, String token, String body)
            throws Exception {
        return send(method, path, token, body, SameTokens.current());
    }

    private static HttpResponse<String> send(String method, String path, String token, String body, String sameToken)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (sameToken != null) request.header(SameTokens.HEADER, sameToken);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String edit(long version, String title) {
        return "{\"expectedVersion\":" + version + ",\"title\":\"" + title + "\",\"content\":\"\"}";
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static final String READ = NotePermissions.READ;
    private static final String WRITE = NotePermissions.WRITE;
    private static final String BOTH = "\"" + READ + "\",\"" + WRITE + "\"";

    @Test
    void missingTokenIsUnauthenticated() throws Exception {
        assertEquals(401, call("GET", "/api/me/notes", null, null).statusCode());
    }

    // 绕过网关的直连（缺少或伪造 Same-Token）即使带着有效登录令牌也被拒绝，不进入业务处理。
    @Test
    void directCallWithoutSameTokenIsRejected() throws Exception {
        String token = user(401, BOTH, scope(true, false, ""), scope(true, false, ""));
        assertEquals(401, send("GET", "/api/notes", token, null, null).statusCode());
        assertEquals(401, send("GET", "/api/notes", token, null, "forged").statusCode());
        var accepted = call("GET", "/api/notes", token, null);
        assertEquals(200, accepted.statusCode());
        assertNotNull(accepted.headers().firstValue("X-Trace-Id").orElse(null));
    }

    // 写范围决定能在哪些组织下创建；读范围决定列表可见；本人便笺是只需登录的纯查询。
    @Test
    void scopesDecideWritesAndVisibility() throws Exception {
        String east = user(101, BOTH, scope(false, true, "\"10\""), scope(false, true, "\"10\""));
        var created = call("POST", "/api/notes", east, "{\"unitId\":\"10\",\"title\":\"华东\",\"content\":\"a\"}");
        assertEquals("SUCCESS", json(created).get("code").asString());
        assertEquals(403, call("POST", "/api/notes", east, "{\"unitId\":\"20\",\"title\":\"越界\",\"content\":\"\"}")
                .statusCode());
        String self = user(102, BOTH, scope(false, true, ""), scope(false, true, ""));
        call("POST", "/api/notes", self, "{\"title\":\"本人\",\"content\":\"\"}");
        assertEquals(1, json(call("GET", "/api/notes", self, null)).get("data").size());
        assertEquals(1, json(call("GET", "/api/notes", east, null)).get("data").size());
        String all = user(103, "\"" + READ + "\"", scope(true, false, ""), scope(false, false, ""));
        assertEquals(2, json(call("GET", "/api/notes", all, null)).get("data").size());
        String none = user(104, "\"" + READ + "\"", scope(false, false, ""), scope(false, false, ""));
        assertEquals(0, json(call("GET", "/api/notes", none, null)).get("data").size());
        assertEquals(1, json(call("GET", "/api/me/notes", east, null)).get("data").size());
    }

    // 创建便笺与 NoteCreated 同事务写入 Outbox；被拒绝的创建（业务回滚）不留下事件。
    @Test
    void creationPublishesEventInSameTransaction() throws Exception {
        String writer = user(601, BOTH, scope(false, true, ""), scope(false, true, ""));
        String id = json(call("POST", "/api/notes", writer, "{\"title\":\"有事件\",\"content\":\"\"}"))
                .get("data").get("id").asString();
        call("POST", "/api/notes", writer, "{\"title\":\" \",\"content\":\"\"}");
        var jdbc = application.getBean(JdbcTemplate.class);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM domainforge_outbox", Integer.class));
        var event = jdbc.queryForMap("SELECT topic, event_type, event_key, payload FROM domainforge_outbox");
        assertEquals("@@ARTIFACT@@-notes", event.get("topic"));
        assertEquals("NoteCreated", event.get("event_type"));
        assertEquals(id, event.get("event_key"));
        assertTrue(event.get("payload").toString().contains("\"ownerId\":\"601\""));
    }

    // 权限码缺失 403；过期版本为业务冲突（HTTP 200 + 稳定代码）；范围外对象按不存在处理。
    @Test
    void permissionsVersionsAndHiddenObjects() throws Exception {
        String writer = user(201, BOTH, scope(false, true, ""), scope(false, true, ""));
        String reader = user(202, "\"" + READ + "\"", scope(true, false, ""), scope(false, false, ""));
        assertEquals(403, call("POST", "/api/notes", reader, "{\"title\":\"x\",\"content\":\"\"}").statusCode());
        String id = json(call("POST", "/api/notes", writer, "{\"title\":\"原\",\"content\":\"\"}"))
                .get("data").get("id").asString();
        var edited = call("PUT", "/api/notes/" + id, writer, edit(1, "新"));
        assertEquals(2, json(edited).get("data").get("version").asInt());
        var stale = call("PUT", "/api/notes/" + id, writer, edit(1, "旧"));
        assertEquals("NOTE_CONFLICT", json(stale).get("code").asString());
        String other = user(203, BOTH, scope(false, true, ""), scope(false, true, ""));
        var hidden = call("PUT", "/api/notes/" + id, other, edit(2, "改"));
        assertEquals("NOTE_NOT_FOUND", json(hidden).get("code").asString());
        var invalid = call("POST", "/api/notes", writer, "{\"title\":\" \",\"content\":\"\"}");
        assertEquals("NOTE_INVALID", json(invalid).get("code").asString());
    }

    // IAM 不可用时拒绝为 503，不降级为匿名或允许。
    @Test
    void iamFailureIsUnavailable() throws Exception {
        String token = user(301, BOTH, scope(true, false, ""), scope(true, false, ""));
        IAM_DOWN.set(true);
        assertEquals(503, call("GET", "/api/notes", token, null).statusCode());
        IAM_DOWN.set(false);
        assertEquals(200, call("GET", "/api/notes", token, null).statusCode());
    }
}
