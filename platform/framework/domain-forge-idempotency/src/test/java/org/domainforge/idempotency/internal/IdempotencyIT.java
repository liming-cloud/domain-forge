package org.domainforge.idempotency.internal;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.api.SameTokens;
import org.domainforge.idempotency.api.Idempotent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Redis 上、两个应用实例之间的幂等合同：重复请求返回首次结果（跨实例）、请求体不同 422、首次仍在处理 409、
 * 5xx 不保存可重试、账号之间互不影响、无键与非幂等入口照常执行、键格式与未登录、Redis 不可用 503。
 */
class IdempotencyIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, AtomicInteger> EXECUTED = new ConcurrentHashMap<>();
    private static ConfigurableApplicationContext first;
    private static ConfigurableApplicationContext second;
    private static String alice;
    private static String bob;

    @BeforeAll
    static void start() {
        assertNotNull(System.getenv("REDIS_PORT"), "需要真实 Redis 验证环境");
        first = launch();
        second = launch();
        alice = login(first, 701);
        bob = login(first, 702);
    }

    @AfterAll
    static void stop() {
        if (first != null) first.close();
        if (second != null) second.close();
    }

    private static ConfigurableApplicationContext launch() {
        return new SpringApplicationBuilder(Application.class).run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.application.name=idempotency-it",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + System.getenv("REDIS_PORT"),
                "--spring.data.redis.password=" + System.getenv("REDIS_PASSWORD"),
                "--domainforge.idempotency.lease=PT5S");
    }

    private static String login(ConfigurableApplicationContext context, long account) {
        String[] token = new String[1];
        StpLogic logic = context.getBean(StpLogic.class);
        SaTokenContextMockUtil.setMockContext(() -> {
            logic.login(account);
            token[0] = logic.getTokenValue();
        });
        return token[0];
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext context, String path, String token,
                                             String key, String body) throws Exception {
        return post(context, path, token, key, body, SameTokens.current());
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext context, String path, String token,
                                             String key, String body, String sameToken) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + context.getBean(Environment.class).getProperty("local.server.port") + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header(SameTokens.HEADER, sameToken);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int executed(String body) {
        return EXECUTED.getOrDefault(body, new AtomicInteger()).get();
    }

    private static String body() {
        return "{\"order\":\"" + UUID.randomUUID() + "\"}";
    }

    // 同一键的重复请求（含另一个实例）原样返回首次结果，只执行一次。
    @Test
    void repeatsReplayFirstResult() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = body();
        var created = post(first, "/api/orders", alice, key, body);
        assertEquals(201, created.statusCode(), created.body());
        var again = post(first, "/api/orders", alice, key, body);
        var elsewhere = post(second, "/api/orders", alice, key, body);
        for (var replay : new HttpResponse[]{again, elsewhere}) {
            assertEquals(201, replay.statusCode());
            assertEquals(created.body(), replay.body());
            assertEquals("true", replay.headers().firstValue("Idempotent-Replayed").orElse(null));
            assertTrue(replay.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        }
        assertEquals(1, executed(body));
    }

    // 同一键但请求体不同 422，不执行。
    @Test
    void reusedKeyWithDifferentBodyIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        post(first, "/api/orders", alice, key, body());
        String other = body();
        var reused = post(second, "/api/orders", alice, key, other);
        assertEquals(422, reused.statusCode());
        assertTrue(reused.body().contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\""));
        assertEquals(0, executed(other));
    }

    // 首次请求仍在处理时，另一个实例收到的重复请求 409；完成后重复请求返回首次结果。
    @Test
    void concurrentRepeatIsInProgress() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"order\":\"slow-" + UUID.randomUUID() + "\"}";
        CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return post(first, "/api/orders", alice, key, body);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (executed(body) == 0 && System.nanoTime() < deadline) Thread.sleep(20);
        var during = post(second, "/api/orders", alice, key, body);
        assertEquals(409, during.statusCode());
        assertTrue(during.body().contains("\"code\":\"IDEMPOTENCY_IN_PROGRESS\""));
        var done = slow.get();
        assertEquals(201, done.statusCode());
        assertEquals(done.body(), post(second, "/api/orders", alice, key, body).body());
        assertEquals(1, executed(body));
    }

    // 首次结果为 5xx 时不保存，同一键可以重试并再次执行。
    @Test
    void serverFailuresAreNotRemembered() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = body();
        assertEquals(500, post(first, "/api/failing", alice, key, body).statusCode());
        assertEquals(500, post(second, "/api/failing", alice, key, body).statusCode());
        assertEquals(2, executed(body));
    }

    // 不同账号的同一键互不影响；不带键、或非幂等入口照常每次执行。
    @Test
    void scopeIsAccountAndEndpoint() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = body();
        post(first, "/api/orders", alice, key, body);
        assertNotEquals("true", post(first, "/api/orders", bob, key, body).headers()
                .firstValue("Idempotent-Replayed").orElse("false"));
        assertEquals(2, executed(body));
        String plain = body();
        post(first, "/api/orders", alice, null, plain);
        post(first, "/api/orders", alice, null, plain);
        post(first, "/api/plain", alice, key, plain);
        post(first, "/api/plain", alice, key, plain);
        assertEquals(4, executed(plain));
    }

    // 键格式不合法 400；未登录照常交给入口拒绝（401）。
    @Test
    void invalidKeyAndAnonymous() throws Exception {
        var invalid = post(first, "/api/orders", alice, "has space", body());
        assertEquals(400, invalid.statusCode());
        assertEquals(401, post(first, "/api/orders", null, UUID.randomUUID().toString(), body()).statusCode());
    }

    // 会话 Redis 不可用：整条链路拒绝（503）且不执行业务（过滤器自身的存储故障路径见 IdempotencyFilterTest）。
    @Test
    void redisOutageRejects() throws Exception {
        String sameToken = SameTokens.current();
        String body = body();
        var process = new ProcessBuilder("docker", "pause", System.getenv("REDIS_CONTAINER")).start();
        assertEquals(0, process.waitFor());
        try {
            assertEquals(503, post(first, "/api/orders", alice, UUID.randomUUID().toString(), body, sameToken)
                    .statusCode());
        } finally {
            assertEquals(0, new ProcessBuilder("docker", "unpause", System.getenv("REDIS_CONTAINER")).start()
                    .waitFor());
        }
        assertEquals(0, executed(body));
    }

    /**
     * 测试入口：订单创建（幂等，返回 201）、总是失败的入口（幂等）、普通入口。
     */
    @RestController
    static class Orders {
        private final AtomicInteger ids = new AtomicInteger();

        @SaCheckLogin
        @Idempotent
        @PostMapping(value = "/api/orders", produces = "application/json")
        org.springframework.http.ResponseEntity<String> create(@RequestBody String body) throws Exception {
            EXECUTED.computeIfAbsent(body, ignored -> new AtomicInteger()).incrementAndGet();
            if (body.contains("slow-")) Thread.sleep(1500);
            return org.springframework.http.ResponseEntity.status(201)
                    .body("{\"id\":" + ids.incrementAndGet() + ",\"at\":\"" + System.nanoTime() + "\"}");
        }

        @SaCheckLogin
        @Idempotent
        @PostMapping("/api/failing")
        String failing(@RequestBody String body) {
            EXECUTED.computeIfAbsent(body, ignored -> new AtomicInteger()).incrementAndGet();
            throw new IllegalStateException("模拟服务端失败");
        }

        @SaCheckLogin
        @PostMapping("/api/plain")
        String plain(@RequestBody String body) {
            EXECUTED.computeIfAbsent(body, ignored -> new AtomicInteger()).incrementAndGet();
            return "{}";
        }
    }

    /**
     * 自动配置（cache、access-control、幂等）＋测试入口。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(Orders.class)
    static class Application {
        /**
         * 本测试只用纯登录校验：主体即会话中的账号，没有权限；服务中由 IAM 适配器提供。
         */
        @org.springframework.context.annotation.Bean
        org.domainforge.access.api.CurrentSubjectSource currentSubjectSource() {
            return token -> new org.domainforge.shared.CurrentSubject(
                    Long.parseLong(String.valueOf(cn.dev33.satoken.stp.StpUtil.getLoginIdByToken(token))),
                    java.util.Set.of());
        }
    }
}
