package org.domainforge.servicecall.internal;

import cn.dev33.satoken.same.SaSameUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.domainforge.servicecall.api.ServiceCallException;
import org.domainforge.servicecall.api.ServiceCallFailure;
import org.domainforge.servicecall.api.ServiceCalls;
import org.domainforge.shared.ApiResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Boot 与 OpenFeign 装配下的跨服务调用合同：身份与请求标识传递、失败分类、默认不重试。
 *
 * <p>被调方用本地 HTTP 替身；Same-Token 使用 Sa-Token 原生内存存储。
 */
class ServiceCallIntegrationTest {
    private static final Map<String, Map<String, String>> SEEN = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static HttpServer server;
    private static ConfigurableApplicationContext context;
    private static StubClient stub;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ServiceCallIntegrationTest::answer);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--stub.url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--refused.url=http://127.0.0.1:" + closedPort(),
                "--spring.cloud.openfeign.client.config.stub.read-timeout=500");
        stub = context.getBean(StubClient.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (server != null) server.stop(0);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * /status/{code} 按指定状态返回外壳；/slow 超过读取超时才应答；记录每个路径收到的关键请求头与次数。
     */
    private static void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        CALLS.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        var headers = new ConcurrentHashMap<String, String>();
        for (String name : new String[]{"SA-SAME-TOKEN", "Authorization", "X-Request-Id"}) {
            var values = exchange.getRequestHeaders().get(name);
            if (values != null) headers.put(name, String.join("|", values));
        }
        SEEN.put(path, headers);
        if (path.equals("/slow")) sleep(1500);
        int status = path.startsWith("/status/") ? Integer.parseInt(path.substring("/status/".length())) : 200;
        String code = switch (status) {
            case 200 -> path.equals("/rejected") ? "NOTE_CONFLICT" : "SUCCESS";
            case 409 -> "NOTE_CONFLICT";
            default -> "X";
        };
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"对方的提示\",\"data\":\"value\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
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

    private static void inRequest(String authorization, String requestId) {
        var request = new MockHttpServletRequest();
        if (authorization != null) request.addHeader("Authorization", authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        if (requestId != null) MDC.put("requestId", requestId);
    }

    private static ServiceCallException failure(Executable call) {
        return assertThrows(ServiceCallException.class, call);
    }

    // 请求内调用：Same-Token 为当前值，用户令牌与请求标识原样转发；客户端伪造的 Same-Token 被替换。
    @Test
    void propagatesIdentityWithinRequest() {
        inRequest("Bearer user-token", "rid-1");
        assertEquals("value", ServiceCalls.data(stub.ok("forged")));
        var seen = SEEN.get("/ok");
        assertEquals(SaSameUtil.getToken(), seen.get("SA-SAME-TOKEN"));
        assertEquals("Bearer user-token", seen.get("Authorization"));
        assertEquals("rid-1", seen.get("X-Request-Id"));
    }

    // 客户端显式声明 Authorization 时不重复添加；没有用户上下文时只带 Same-Token。
    @Test
    void explicitOrMissingUserToken() {
        inRequest("Bearer from-request", null);
        stub.explicit("Bearer explicit");
        assertEquals("Bearer explicit", SEEN.get("/explicit").get("Authorization"));
        RequestContextHolder.resetRequestAttributes();
        stub.background();
        var seen = SEEN.get("/background");
        assertNull(seen.get("Authorization"));
        assertNull(seen.get("X-Request-Id"));
        assertEquals(SaSameUtil.getToken(), seen.get("SA-SAME-TOKEN"));
    }

    // 401、403 保持语义；4xx 与外壳中的非成功代码为对方的拒绝并带代码；5xx 不可用；504 结果未知。
    @Test
    void classifiesResponses() {
        assertEquals(ServiceCallFailure.UNAUTHENTICATED, failure(() -> stub.status(401)).failure());
        assertEquals(ServiceCallFailure.FORBIDDEN, failure(() -> stub.status(403)).failure());
        var conflict = failure(() -> stub.status(409));
        assertEquals(ServiceCallFailure.REJECTED, conflict.failure());
        assertEquals("NOTE_CONFLICT", conflict.code());
        var rejected = failure(() -> ServiceCalls.data(stub.rejected()));
        assertEquals(ServiceCallFailure.REJECTED, rejected.failure());
        assertEquals("NOTE_CONFLICT", rejected.code());
        assertFalse(rejected.getMessage().contains("对方的提示"));
        assertEquals(ServiceCallFailure.UNAVAILABLE, failure(() -> stub.status(503)).failure());
        assertEquals(ServiceCallFailure.UNKNOWN, failure(() -> stub.status(504)).failure());
    }

    // 连接被拒确定未发出为不可用；读取超时结果未知；两者与 5xx 都只调用一次，不自动重试。
    @Test
    void transportFailuresAreNotRetried() {
        assertEquals(ServiceCallFailure.UNAVAILABLE,
                failure(() -> context.getBean(RefusedClient.class).ok()).failure());
        assertEquals(ServiceCallFailure.UNKNOWN, failure(() -> stub.slow()).failure());
        assertEquals(1, CALLS.get("/slow").get());
        failure(() -> stub.status(500));
        assertEquals(1, CALLS.get("/status/500").get());
    }

    // 经负载均衡调用但没有任何实例：请求未发出，不可用。
    @Test
    void missingInstanceIsUnavailable() {
        assertEquals(ServiceCallFailure.UNAVAILABLE,
                failure(() -> context.getBean(AbsentClient.class).ok()).failure());
    }

    /**
     * 只启用自动配置与测试客户端。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableFeignClients(clients = {StubClient.class, RefusedClient.class, AbsentClient.class})
    static class Application {
    }

    /**
     * 指向本地替身的客户端。
     */
    @FeignClient(name = "stub", url = "${stub.url}")
    interface StubClient {
        @GetMapping("/ok")
        ApiResponse<String> ok(@RequestHeader("SA-SAME-TOKEN") String forged);

        @GetMapping("/rejected")
        ApiResponse<String> rejected();

        @GetMapping("/explicit")
        ApiResponse<String> explicit(@RequestHeader("Authorization") String authorization);

        @GetMapping("/background")
        ApiResponse<String> background();

        @GetMapping("/status/{status}")
        ApiResponse<String> status(@PathVariable("status") int status);

        @GetMapping("/slow")
        ApiResponse<String> slow();
    }

    /**
     * 指向未监听端口的客户端。
     */
    @FeignClient(name = "refused", url = "${refused.url}")
    interface RefusedClient {
        @GetMapping("/ok")
        ApiResponse<String> ok();
    }

    /**
     * 经负载均衡、没有任何实例的客户端。
     */
    @FeignClient(name = "absent")
    interface AbsentClient {
        @GetMapping("/ok")
        ApiResponse<String> ok();
    }
}
