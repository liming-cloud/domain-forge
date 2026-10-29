package org.domainforge.gateway;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.same.SaSameUtil;
import cn.dev33.satoken.stp.StpLogic;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.gateway.bootstrap.GatewayApplication;
import org.domainforge.gateway.infrastructure.security.SameTokenRotation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Redis 与开启鉴权的 Nacos 上验证网关合同（设计见 docs/architecture/06-API网关.md）：转发与身份头、
 * 登录前置、禁止路径、限流、下游故障、路由动态更新、Same-Token 轮换与 Redis 中断。
 *
 * <p>后端用本地 HTTP 替身记录收到的请求；服务实例经简单发现给出，路由与限流从 Nacos 的 gateway.yaml 读取。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayIT {
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final Map<String, Map<String, String>> RECEIVED = new ConcurrentHashMap<>();
    private static final String BASE_ROUTES = String.join("\n",
            "    routes:",
            "      - id: iam",
            "        service: iam",
            "        paths: [/api/**]",
            "        anonymous: [POST /api/auth/login]",
            "      - id: notes",
            "        service: notes",
            "        paths: [/api/notes/**, /api/me/notes]",
            "      - id: ghost",
            "        service: ghost",
            "        paths: [/api/ghost/**]");
    private static String nacos;
    private static String nacosToken;
    private static HttpServer iam;
    private static HttpServer notes;
    private static ConfigurableApplicationContext gateway;
    private static String base;
    private static String session;

    @BeforeAll
    static void start() throws Exception {
        assertNotNull(System.getenv("NACOS_PORT"), "需要真实 Nacos 与 Redis 验证环境");
        int http = Integer.parseInt(System.getenv("NACOS_PORT"));
        System.setProperty("nacos.server.grpc.port.offset",
                Integer.toString(Integer.parseInt(System.getenv("NACOS_GRPC_PORT")) - http));
        nacos = "http://127.0.0.1:" + http + "/nacos/v3";
        nacos("POST", "/auth/user/admin", Map.of("password", System.getenv("NACOS_ADMIN_PASSWORD")));
        String login = nacos("POST", "/auth/user/login",
                Map.of("username", "nacos", "password", System.getenv("NACOS_ADMIN_PASSWORD"))).body();
        nacosToken = login.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        publish(BASE_ROUTES, 1, 5, "replenish-per-second: 1");
        iam = stub("iam");
        notes = stub("notes");
        gateway = new SpringApplicationBuilder(GatewayApplication.class).run(
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.profiles.active=config-center",
                "--server.port=0",
                "--management.server.port=0",
                "--GATEWAY_NACOS_SERVER_ADDR=127.0.0.1:" + http,
                "--GATEWAY_NACOS_USERNAME=nacos",
                "--GATEWAY_NACOS_PASSWORD=" + System.getenv("NACOS_ADMIN_PASSWORD"),
                "--GATEWAY_REDIS_HOST=127.0.0.1",
                "--GATEWAY_REDIS_PORT=" + System.getenv("REDIS_PORT"),
                "--GATEWAY_REDIS_PASSWORD=" + System.getenv("REDIS_PASSWORD"),
                "--GATEWAY_TRUSTED_PROXIES=127.0.0.1",
                "--GATEWAY_RESPONSE_TIMEOUT=1s",
                "--spring.cloud.discovery.client.simple.instances.iam[0].uri=" + uri(iam),
                "--spring.cloud.discovery.client.simple.instances.notes[0].uri=" + uri(notes));
        base = "http://127.0.0.1:" + gateway.getBean(Environment.class).getProperty("local.server.port");
        session = gateway.getBean(StpLogic.class).createLoginSession(1001L);
    }

    @AfterAll
    static void stop() {
        if (gateway != null) gateway.close();
        if (iam != null) iam.stop(0);
        if (notes != null) notes.stop(0);
    }

    private static String uri(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 后端替身：记录最近一次请求的路径与关键请求头；/slow 结尾的路径超过网关的响应超时才应答。
     */
    private static HttpServer stub(String name) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> answer(name, exchange));
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static void answer(String name, HttpExchange exchange) throws IOException {
        var headers = exchange.getRequestHeaders();
        var seen = new ConcurrentHashMap<String, String>();
        seen.put("path", exchange.getRequestURI().getRawPath());
        for (String header : new String[]{"Authorization", "traceparent", "X-Forwarded-For", "SA-SAME-TOKEN"}) {
            String value = headers.getFirst(header);
            if (value != null) seen.put(header, value);
        }
        RECEIVED.put(name, seen);
        if (exchange.getRequestURI().getPath().endsWith("/slow")) sleep(3000);
        byte[] body = ("{\"code\":\"SUCCESS\",\"message\":\"成功\",\"data\":\"" + name + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // 与后端的追踪组件一样回传 traceId，验证网关响应中不会出现两个值。
        String traceparent = headers.getFirst("traceparent");
        if (traceparent != null) exchange.getResponseHeaders().add("X-Trace-Id", traceparent.split("-")[1]);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static HttpResponse<String> nacos(String method, String path, Map<String, String> form)
            throws Exception {
        String encoded = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(nacos + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(encoded));
        if (nacosToken != null) request.header("accessToken", nacosToken);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 发布 gateway.yaml，并等到客户端读取路径能读到（发布后数百毫秒内仍返回旧内容）。
     */
    private static void publish(String routes, int replenish, int burst, String marker) throws Exception {
        String content = String.join("\n",
                "domainforge:",
                "  config:",
                "    source: nacos",
                "gateway:",
                "  routing:",
                "    rate-limit:",
                "      replenish-per-second: " + replenish,
                "      burst: " + burst,
                routes,
                "");
        var response = nacos("POST", "/admin/cs/config", Map.of(
                "dataId", "gateway.yaml", "groupName", "DEFAULT_GROUP", "content", content, "type", "yaml"));
        assertEquals(200, response.statusCode(), response.body());
        await("发布内容可读", () -> {
            try {
                var query = HttpRequest.newBuilder(URI.create(
                                nacos + "/client/cs/config?dataId=gateway.yaml&groupName=DEFAULT_GROUP"))
                        .header("accessToken", nacosToken).GET().build();
                return HTTP.send(query, HttpResponse.BodyHandlers.ofString()).body().contains(marker);
            } catch (Exception unavailable) {
                return false;
            }
        });
    }

    private static HttpResponse<String> call(String method, String path, String client, Map<String, String> headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("X-Forwarded-For", client);
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String client) throws Exception {
        return call("GET", path, client, Map.of("Authorization", "Bearer " + session));
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        await(what, Duration.ofSeconds(30), condition);
    }

    private static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail(timeout.toSeconds() + " 秒内未观察到：" + what);
    }

    private static int status(String path, String client) {
        try {
            return get(path, client).statusCode();
        } catch (Exception failure) {
            return -1;
        }
    }

    private static String body(String path, String client) {
        try {
            return get(path, client).body();
        } catch (Exception failure) {
            return "";
        }
    }

    private static double counter(String name, String... tags) {
        var search = gateway.getBean(MeterRegistry.class).find(name);
        for (int index = 0; index < tags.length; index += 2) search = search.tag(tags[index], tags[index + 1]);
        var found = search.counter();
        return found == null ? 0 : found.count();
    }

    // 最长前缀决定服务；令牌与客户端地址原样到达，链路上下文传到后端且与响应头的 traceId 一致；伪造的 Same-Token 被替换。
    @Test
    @Order(1)
    void forwardsWithIdentityHeaders() throws Exception {
        var response = call("GET", "/api/notes/7", "203.0.113.10", Map.of(
                "Authorization", "Bearer " + session, "SA-SAME-TOKEN", "forged"));
        assertEquals(200, response.statusCode(), () -> response.body() + " DEBUG tokenName="
                + SaManager.getConfig().getTokenName() + " loginId="
                + gateway.getBean(StpLogic.class).getLoginIdNotHandle(session) + " byToken="
                + gateway.getBean(StpLogic.class).getLoginIdByToken(session) + " dao="
                + SaManager.getSaTokenDao().getClass().getSimpleName());
        List<String> traceIds = response.headers().allValues("X-Trace-Id");
        assertEquals(1, traceIds.size(), traceIds.toString());
        assertTrue(traceIds.getFirst().matches("[0-9a-f]{32}"), traceIds.toString());
        var seen = RECEIVED.get("notes");
        assertEquals("/api/notes/7", seen.get("path"));
        assertEquals("Bearer " + session, seen.get("Authorization"));
        assertTrue(seen.get("traceparent").startsWith("00-" + traceIds.getFirst() + "-"), seen.get("traceparent"));
        assertEquals("203.0.113.10", seen.get("X-Forwarded-For"));
        assertEquals(SaSameUtil.getTokenNh(), seen.get("SA-SAME-TOKEN"));
        assertTrue(get("/api/me/notes", "203.0.113.10").body().contains("notes"));
        assertTrue(get("/api/me", "203.0.113.10").body().contains("iam"));
        String iamTrace = RECEIVED.get("iam").get("traceparent");
        assertTrue(iamTrace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"), iamTrace);
    }

    // 未登录、无效令牌、只在参数中给令牌都在网关拒绝；匿名清单中的登录接口放行。
    @Test
    @Order(2)
    void rejectsUnauthenticatedExceptAnonymous() throws Exception {
        String client = "203.0.113.20";
        var missing = call("GET", "/api/notes/1", client, Map.of());
        assertEquals(401, missing.statusCode());
        assertTrue(missing.body().contains("\"code\":\"UNAUTHENTICATED\""));
        assertEquals(401, call("GET", "/api/notes/1", client, Map.of("Authorization", "Bearer bogus")).statusCode());
        assertEquals(401, call("GET", "/api/notes/1?Authorization=" + session, client, Map.of()).statusCode());
        assertEquals(200, call("POST", "/api/auth/login", client, Map.of()).statusCode());
        assertEquals(401, call("GET", "/api/auth/login", client, Map.of()).statusCode());
    }

    // /internal/、管理端点不可路由；可能被后端规范化成其他路径的写法在路由前拒绝。
    @Test
    @Order(3)
    void forbiddenAndAmbiguousPaths() throws Exception {
        String client = "203.0.113.30";
        assertEquals(404, get("/internal/access/current", client).statusCode());
        assertEquals(404, get("/actuator/health", client).statusCode());
        assertEquals(404, call("GET", "/actuator/health", client, Map.of()).statusCode());
        // 管理端口复用主应用的 WebFilter：健康检查不能被登录校验拦下（容器健康检查依赖它）。
        String management = gateway.getBean(Environment.class).getProperty("local.management.port");
        var health = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + management + "/actuator/health/readiness")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), health.body());
        assertEquals(400, get("/api/%2e%2e/internal/access/current", client).statusCode());
        assertEquals(400, get("/api/..;/internal/access/current", client).statusCode());
        assertEquals(400, get("/api/notes/%2F..%2Finternal", client).statusCode());
    }

    // 无可用实例 503、下游超时 504，均为统一外壳。
    @Test
    @Order(4)
    void downstreamFailures() throws Exception {
        var missing = get("/api/ghost/1", "203.0.113.40");
        assertEquals(503, missing.statusCode());
        assertTrue(missing.body().contains("\"code\":\"SERVICE_UNAVAILABLE\""));
        var slow = get("/api/notes/slow", "203.0.113.40");
        assertEquals(504, slow.statusCode());
        assertTrue(slow.body().contains("\"code\":\"GATEWAY_TIMEOUT\""));
    }

    // 同一客户端地址超过桶容量后 429；其他地址不受影响。
    @Test
    @Order(5)
    void rateLimitsPerClientAddress() throws Exception {
        String client = "203.0.113.50";
        for (int index = 0; index < 5; index++)
            assertEquals(200, call("POST", "/api/auth/login", client, Map.of()).statusCode(), "第 " + index + " 次");
        var limited = call("POST", "/api/auth/login", client, Map.of());
        assertEquals(429, limited.statusCode());
        assertTrue(limited.body().contains("\"code\":\"RATE_LIMITED\""));
        assertEquals("1", limited.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(200, call("POST", "/api/auth/login", "203.0.113.51", Map.of()).statusCode());
    }

    // 运行中发布新路由立即生效；非法发布整组不应用，原路由继续工作并计 rejected。
    @Test
    @Order(6)
    void routesFollowNacosPublications() throws Exception {
        String client = "203.0.113.60";
        assertTrue(get("/api/extra/1", client).body().contains("iam"), "发布前由 IAM 的 /api/** 承接");
        String extra = BASE_ROUTES + "\n      - id: extra\n        service: notes\n        paths: [/api/extra/**]";
        publish(extra, 5, 50, "/api/extra/**");
        await("新路由生效", () -> body("/api/extra/1", client).contains("notes"));
        double rejected = counter("domainforge.config.refresh", "group", "gateway.routing", "result", "rejected");
        publish(BASE_ROUTES + "\n      - id: bad\n        service: notes\n        paths: [/internal/**]", 5, 50,
                "/internal/**");
        await("非法发布被拒绝", () -> counter("domainforge.config.refresh", "group", "gateway.routing", "result", "rejected")
                == rejected + 1);
        assertTrue(get("/api/extra/1", client).body().contains("notes"));
        assertEquals(404, get("/internal/access/current", client).statusCode());
    }

    // 按固定间隔刷新：刚刷新过（其他实例或启动时）不重复刷新；满一个间隔后新值用于转发，上一个值保留供在途请求使用。
    @Test
    @Order(7)
    void sameTokenRefreshesOncePerInterval() throws Exception {
        var rotation = gateway.getBean(SameTokenRotation.class);
        String before = SaSameUtil.getTokenNh();
        assertNotNull(before, "启动后应已生成 Same-Token");
        rotation.refreshIfDue();
        assertEquals(before, SaSameUtil.getTokenNh());
        long timeout = SaManager.getConfig().getSameTokenTimeout();
        SaManager.getSaTokenDao().updateTimeout(SaManager.getSaSameTemplate().splicingTokenSaveKey(), timeout - 1800);
        rotation.refreshIfDue();
        String after = SaSameUtil.getTokenNh();
        assertNotEquals(before, after);
        assertEquals(before, SaSameUtil.getPastTokenNh());
        assertEquals(200, get("/api/notes/2", "203.0.113.70").statusCode());
        assertEquals(after, RECEIVED.get("notes").get("SA-SAME-TOKEN"));
    }

    // Redis 中断：会话与 Same-Token 无法读取时 503，不放行；限流放行并计数；恢复后正常转发。
    @Test
    @Order(8)
    void redisOutageFailsClosedForIdentity() throws Exception {
        String client = "203.0.113.80";
        double unavailable = counter("domainforge.gateway.rate-limit.unavailable");
        docker("pause");
        try {
            assertEquals(503, get("/api/notes/3", client).statusCode());
            assertEquals(503, call("POST", "/api/auth/login", client, Map.of()).statusCode());
            assertTrue(counter("domainforge.gateway.rate-limit.unavailable") >= unavailable + 2);
        } finally {
            docker("unpause");
        }
        await("Redis 恢复后正常转发", () -> status("/api/notes/3", client) == 200);
    }

    /**
     * 暂停而不是停止容器：随机端口映射在容器重启后会变化。
     */
    private static void docker(String command) throws Exception {
        var process = new ProcessBuilder("docker", command, System.getenv("REDIS_CONTAINER"))
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), "docker " + command + " 失败");
    }
}
