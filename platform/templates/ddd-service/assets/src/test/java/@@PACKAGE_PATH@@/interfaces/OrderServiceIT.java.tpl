package @@PACKAGE@@.interfaces;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.StatusUtil;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpLogic;
import @@PACKAGE@@.application.order.OrderPermissions;
import @@PACKAGE@@.bootstrap.@@APP_CLASS@@;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.domainforge.access.api.SameTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 与会话 Redis 上的服务接入：IAM 由本地 HTTP 替身按公开合同应答，验证本服务的 Feign 适配、
 * 权限注解、数据范围过滤、业务拒绝与依赖故障。
 */
class OrderServiceIT {
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
        iam.createContext("/internal/access/", OrderServiceIT::answer);
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
                "--domainforge.files.local.root=" + Files.createTempDirectory("@@ARTIFACT@@-files-"),
                "--spring.servlet.multipart.max-file-size=64KB",
                "--spring.servlet.multipart.max-request-size=70KB",
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
        application.getBean(JdbcTemplate.class)
                .update("TRUNCATE orders, order_line, order_file, domainforge_file, domainforge_outbox");
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
        byte[] bytes = (status == 200 ? body
                : "{\"type\":\"about:blank\",\"status\":" + status + ",\"detail\":\"x\",\"code\":\"X\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "application/problem+json");
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

    /**
     * 以 multipart 上传一个凭证（字段 file）。
     */
    private static HttpResponse<String> upload(String orderId, String token, String name, String type, byte[] content)
            throws Exception {
        String boundary = "----df" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        var request = HttpRequest.newBuilder(URI.create(base + "/api/orders/" + orderId + "/files"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .header(SameTokens.HEADER, SameTokens.current());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> download(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).GET()
                .header("Authorization", "Bearer " + token)
                .header(SameTokens.HEADER, SameTokens.current());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static final String LINE = "{\"sku\":\"A-1\",\"quantity\":2,\"unitPriceCents\":1500}";

    private static String version(long version) {
        return "{\"expectedVersion\":" + version + "}";
    }

    // 以一行订单行新建草稿订单，返回订单 ID。
    private static String draft(String token, String unit) throws Exception {
        var created = call("POST", "/api/orders", token,
                "{" + (unit == null ? "" : "\"unitId\":\"" + unit + "\",") + "\"lines\":[" + LINE + "]}");
        assertEquals(200, created.statusCode(), created.body());
        return json(created).get("id").asString();
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    // 失败统一为 Problem Details：状态码、类型与扩展字段 code 都确定。
    private static void assertProblem(int status, String code, HttpResponse<?> response) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"),
                response.headers().toString());
        String body = response.body() instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(response.body());
        var problem = JSON.readTree(body);
        assertEquals(status, problem.get("status").asInt());
        assertEquals(code, problem.get("code").asString());
    }

    private static final String READ = OrderPermissions.READ;
    private static final String WRITE = OrderPermissions.WRITE;
    private static final String BOTH = "\"" + READ + "\",\"" + WRITE + "\"";

    @Test
    void missingTokenIsUnauthenticated() throws Exception {
        assertEquals(401, call("GET", "/api/me/orders", null, null).statusCode());
    }

    // 绕过网关的直连（缺少或伪造 Same-Token）即使带着有效登录令牌也被拒绝，不进入业务处理。
    @Test
    void directCallWithoutSameTokenIsRejected() throws Exception {
        String token = user(401, BOTH, scope(true, false, ""), scope(true, false, ""));
        assertEquals(401, send("GET", "/api/orders", token, null, null).statusCode());
        assertEquals(401, send("GET", "/api/orders", token, null, "forged").statusCode());
        var accepted = call("GET", "/api/orders", token, null);
        assertEquals(200, accepted.statusCode());
        assertNotNull(accepted.headers().firstValue("X-Trace-Id").orElse(null));
    }

    // 写范围决定能在哪些组织下创建；读范围决定列表可见；本人订单是只需登录的纯查询；列表带订单行汇总的总额。
    @Test
    void scopesDecideWritesAndVisibility() throws Exception {
        String east = user(101, BOTH, scope(false, true, "\"10\""), scope(false, true, "\"10\""));
        var created = call("POST", "/api/orders", east, "{\"unitId\":\"10\",\"lines\":[" + LINE + "]}");
        assertEquals(200, created.statusCode());
        assertEquals("DRAFT", json(created).get("status").asString());
        assertEquals(403, call("POST", "/api/orders", east, "{\"unitId\":\"20\",\"lines\":[]}").statusCode());
        String self = user(102, BOTH, scope(false, true, ""), scope(false, true, ""));
        draft(self, null);
        assertEquals(1, json(call("GET", "/api/orders", self, null)).size());
        assertEquals(1, json(call("GET", "/api/orders", east, null)).size());
        String all = user(103, "\"" + READ + "\"", scope(true, false, ""), scope(false, false, ""));
        var listed = json(call("GET", "/api/orders", all, null));
        assertEquals(2, listed.size());
        assertEquals(3000, listed.get(0).get("totalCents").asLong());
        String none = user(104, "\"" + READ + "\"", scope(false, false, ""), scope(false, false, ""));
        assertEquals(0, json(call("GET", "/api/orders", none, null)).size());
        assertEquals(1, json(call("GET", "/api/me/orders", east, null)).size());
    }

    // 状态机：草稿 → 已提交 → 已支付；非法迁移 409，取消后不能再动；提交同事务写入 Outbox，被拒绝的提交不留下事件。
    @Test
    void lifecycleFollowsStateMachine() throws Exception {
        String writer = user(601, BOTH, scope(false, true, ""), scope(false, true, ""));
        String empty = json(call("POST", "/api/orders", writer, "{}")).get("id").asString();
        assertProblem(422, "ORDER_EMPTY", call("POST", "/api/orders/" + empty + "/submit", writer, version(1)));
        String id = draft(writer, null);
        assertProblem(409, "ORDER_STATE_CONFLICT", call("POST", "/api/orders/" + id + "/pay", writer, version(1)));
        var submitted = call("POST", "/api/orders/" + id + "/submit", writer, version(1));
        assertEquals("SUBMITTED", json(submitted).get("status").asString());
        assertProblem(409, "ORDER_STATE_CONFLICT",
                call("POST", "/api/orders/" + id + "/lines", writer, "{\"expectedVersion\":2,\"sku\":\"B\",\"quantity\":1,"
                        + "\"unitPriceCents\":100}"));
        var jdbc = application.getBean(JdbcTemplate.class);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM domainforge_outbox", Integer.class));
        var event = jdbc.queryForMap("SELECT topic, event_type, event_key, payload FROM domainforge_outbox");
        assertEquals("@@ARTIFACT@@-orders", event.get("topic"));
        assertEquals("OrderSubmitted", event.get("event_type"));
        assertEquals(id, event.get("event_key"));
        assertTrue(event.get("payload").toString().contains("\"ownerId\":\"601\""));
        assertTrue(event.get("payload").toString().contains("\"totalCents\":\"3000\""));
        assertEquals("PAID", json(call("POST", "/api/orders/" + id + "/pay", writer, version(2))).get("status").asString());
        assertProblem(409, "ORDER_STATE_CONFLICT", call("POST", "/api/orders/" + id + "/cancel", writer, version(3)));
        String other = draft(writer, null);
        assertEquals("CANCELLED", json(call("POST", "/api/orders/" + other + "/cancel", writer, version(1)))
                .get("status").asString());
        assertProblem(409, "ORDER_STATE_CONFLICT", call("POST", "/api/orders/" + other + "/submit", writer, version(2)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM domainforge_outbox", Integer.class));
    }

    // 聚合维护跨行不变式：同一商品只能一行、总额有上限；违反时整个命令回滚，订单行不变。
    @Test
    void aggregateKeepsLineInvariants() throws Exception {
        String writer = user(611, BOTH, scope(false, true, ""), scope(false, true, ""));
        String id = draft(writer, null);
        assertProblem(422, "ORDER_LINE_DUPLICATE", call("POST", "/api/orders/" + id + "/lines", writer,
                "{\"expectedVersion\":1,\"sku\":\"A-1\",\"quantity\":1,\"unitPriceCents\":100}"));
        assertProblem(422, "ORDER_TOTAL_EXCEEDED", call("POST", "/api/orders/" + id + "/lines", writer,
                "{\"expectedVersion\":1,\"sku\":\"B-1\",\"quantity\":999,\"unitPriceCents\":100000000}"));
        assertProblem(400, "ORDER_INVALID", call("POST", "/api/orders/" + id + "/lines", writer,
                "{\"expectedVersion\":1,\"sku\":\" \",\"quantity\":1,\"unitPriceCents\":100}"));
        var added = call("POST", "/api/orders/" + id + "/lines", writer,
                "{\"expectedVersion\":1,\"sku\":\"C-1\",\"quantity\":1,\"unitPriceCents\":500}");
        assertEquals(2, json(added).get("version").asInt());
        var jdbc = application.getBean(JdbcTemplate.class);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_line WHERE order_id = ?", Integer.class,
                Long.parseLong(id)));
    }

    // 权限码缺失 403；过期版本为 409 + 稳定代码；范围外对象按不存在处理。
    @Test
    void permissionsVersionsAndHiddenObjects() throws Exception {
        String writer = user(201, BOTH, scope(false, true, ""), scope(false, true, ""));
        String reader = user(202, "\"" + READ + "\"", scope(true, false, ""), scope(false, false, ""));
        assertEquals(403, call("POST", "/api/orders", reader, "{}").statusCode());
        String id = draft(writer, null);
        var added = call("POST", "/api/orders/" + id + "/lines", writer,
                "{\"expectedVersion\":1,\"sku\":\"B-1\",\"quantity\":1,\"unitPriceCents\":100}");
        assertEquals(2, json(added).get("version").asInt());
        assertProblem(409, "VERSION_CONFLICT", call("POST", "/api/orders/" + id + "/submit", writer, version(1)));
        String other = user(203, BOTH, scope(false, true, ""), scope(false, true, ""));
        assertProblem(404, "ORDER_NOT_FOUND", call("POST", "/api/orders/" + id + "/submit", other, version(2)));
        assertProblem(400, "ORDER_INVALID", call("POST", "/api/orders", writer,
                "{\"lines\":[{\"sku\":\"X\",\"quantity\":0,\"unitPriceCents\":1}]}"));
    }

    // 幂等提交：同一 Idempotency-Key 的重复提交返回首次结果，只有一个事件；同一键换了请求 422。
    @Test
    void repeatedSubmissionIsIdempotent() throws Exception {
        String writer = user(801, BOTH, scope(false, true, ""), scope(false, true, ""));
        String id = draft(writer, null);
        String key = UUID.randomUUID().toString();
        var first = keyed(writer, "/api/orders/" + id + "/submit", key, version(1));
        var again = keyed(writer, "/api/orders/" + id + "/submit", key, version(1));
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(first.body(), again.body());
        assertEquals("true", again.headers().firstValue("Idempotent-Replayed").orElse(null));
        var jdbc = application.getBean(JdbcTemplate.class);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM domainforge_outbox", Integer.class));
        var reused = keyed(writer, "/api/orders/" + id + "/submit", key, version(7));
        assertProblem(422, "IDEMPOTENCY_KEY_REUSED", reused);
        assertProblem(409, "ORDER_STATE_CONFLICT",
                keyed(writer, "/api/orders/" + id + "/submit", UUID.randomUUID().toString(), version(2)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM domainforge_outbox", Integer.class));
    }

    private static HttpResponse<String> keyed(String token, String path, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .header(SameTokens.HEADER, SameTokens.current());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // 凭证经本服务转发：写范围内上传，读范围内列出与下载（凭证响应、内容一致），范围外的订单按不存在处理；
    // 不允许的类型为 422，超过 multipart 上限 413；删除后读取不到。
    @Test
    void attachmentsFollowOrderScopes() throws Exception {
        String writer = user(701, BOTH, scope(false, true, ""), scope(false, true, ""));
        String order = draft(writer, null);
        byte[] content = "第一季度".getBytes(StandardCharsets.UTF_8);
        var upload = upload(order, writer, "报告.txt", "text/plain", content);
        assertEquals(200, upload.statusCode(), upload.body());
        var uploaded = json(upload);
        String file = uploaded.get("id").asString();
        var listed = json(call("GET", "/api/orders/" + order + "/files", writer, null));
        assertEquals(1, listed.size());
        assertEquals("报告.txt", listed.get(0).get("name").asString());
        assertEquals(content.length, listed.get(0).get("size").asInt());
        var downloaded = download("/api/orders/" + order + "/files/" + file, writer);
        assertEquals(200, downloaded.statusCode());
        assertTrue(Arrays.equals(content, downloaded.body()));
        assertTrue(downloaded.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
        assertEquals("nosniff", downloaded.headers().firstValue("X-Content-Type-Options").orElse(null));

        String other = user(702, BOTH, scope(false, true, ""), scope(false, true, ""));
        assertProblem(404, "ORDER_NOT_FOUND", call("GET", "/api/orders/" + order + "/files", other, null));
        assertProblem(404, "ORDER_NOT_FOUND", download("/api/orders/" + order + "/files/" + file, other));
        assertProblem(404, "ORDER_NOT_FOUND", upload(order, other, "a.txt", "text/plain", content));
        String reader = user(703, "\"" + READ + "\"", scope(true, false, ""), scope(false, false, ""));
        assertEquals(403, upload(order, reader, "a.txt", "text/plain", content).statusCode());

        assertProblem(422, "FILE_TYPE_NOT_ALLOWED", upload(order, writer, "a.html", "text/html", content));
        var tooLarge = upload(order, writer, "big.txt", "text/plain", new byte[100 * 1024]);
        assertProblem(413, "PAYLOAD_TOO_LARGE", tooLarge);
        assertProblem(404, "ORDER_FILE_NOT_FOUND", download("/api/orders/" + order + "/files/" + UUID.randomUUID(), writer));

        assertEquals(204, call("DELETE", "/api/orders/" + order + "/files/" + file, writer, null).statusCode());
        assertProblem(404, "ORDER_FILE_NOT_FOUND", download("/api/orders/" + order + "/files/" + file, writer));
        assertEquals(0, json(call("GET", "/api/orders/" + order + "/files", writer, null)).size());
        assertNotNull(application.getBean(JdbcTemplate.class).queryForObject(
                "SELECT deleted_at FROM domainforge_file WHERE id = ?", java.sql.Timestamp.class, file));
    }

    // logback-spring.xml 注册了状态监听器：Logback 的 WARN 状态不以文本打到标准输出，部署环境只有 JSON 日志行。
    @Test
    void logbackHasStatusListener() {
        assertTrue(StatusUtil.contextHasStatusListener((LoggerContext) LoggerFactory.getILoggerFactory()),
                "应注册状态监听器");
    }

    // 管理端口只开放健康检查与 Prometheus 指标；平台组件的指标以 Prometheus 格式输出。
    @Test
    void managementExposesPrometheusOnly() throws Exception {
        String management = "http://127.0.0.1:"
                + application.getBean(Environment.class).getProperty("local.management.port");
        call("GET", "/api/orders", user(1001, BOTH, scope(true, false, ""), scope(true, false, "")), null);
        var metrics = HTTP.send(HttpRequest.newBuilder(URI.create(management + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("http_server_requests_seconds_count"), "应有 HTTP 请求指标");
        assertTrue(metrics.body().contains("application=\"@@ARTIFACT@@\""), "指标带服务名标签");
        for (String hidden : new String[]{"/actuator/env", "/actuator/metrics", "/actuator/beans"})
            assertEquals(404, HTTP.send(HttpRequest.newBuilder(URI.create(management + hidden)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), hidden);
    }

    // IAM 不可用时拒绝为 503，不降级为匿名或允许。
    @Test
    void iamFailureIsUnavailable() throws Exception {
        String token = user(301, BOTH, scope(true, false, ""), scope(true, false, ""));
        IAM_DOWN.set(true);
        assertProblem(503, "SERVICE_UNAVAILABLE", call("GET", "/api/orders", token, null));
        IAM_DOWN.set(false);
        assertEquals(200, call("GET", "/api/orders", token, null).statusCode());
    }
}
