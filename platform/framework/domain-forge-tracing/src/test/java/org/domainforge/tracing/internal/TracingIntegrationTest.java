package org.domainforge.tracing.internal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Boot 装配：响应头返回 traceId，与日志上下文中的 traceId 一致；带 traceparent 的请求延续同一条链路。
 */
class TracingIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static ConfigurableApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(Application.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + context.getBean(Environment.class).getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    private static HttpResponse<String> get(String traceparent) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/probe"));
        if (traceparent != null) request.header("traceparent", traceparent);
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    // 新链路：响应头的 traceId 与处理请求时日志上下文中的 traceId 相同（未采样也有 traceId）。
    @Test
    void responseCarriesTheTraceIdSeenInLogs() throws Exception {
        var response = get(null);
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertTrue(traceId.matches("[0-9a-f]{32}"), traceId);
        assertEquals(traceId, response.body());
    }

    // 带上游 traceparent：延续同一条链路。
    @Test
    void incomingTraceparentContinuesTheTrace() throws Exception {
        var response = get("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", response.body());
    }

    /**
     * 自动配置与一个返回日志上下文 traceId 的端点。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(Probe.class)
    static class Application {
    }

    /**
     * 返回处理请求时日志上下文中的 traceId。
     */
    @RestController
    static class Probe {
        @GetMapping("/probe")
        String probe() {
            return String.valueOf(MDC.get("traceId"));
        }
    }
}
