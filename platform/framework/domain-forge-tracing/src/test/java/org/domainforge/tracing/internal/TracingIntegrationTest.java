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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Boot 装配：响应头返回 traceId，与日志上下文中的 traceId 一致；带 traceparent 的请求延续同一条链路；
 * 部署环境的 ECS 结构化日志中，访问日志带服务名与同一 traceId。
 */
class TracingIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ByteArrayOutputStream LOGS = new ByteArrayOutputStream();
    private static PrintStream console;
    private static ConfigurableApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() {
        // 控制台日志同时写入内存，供核对结构化日志；Logback 的控制台出口每次写入时取当前的 System.out。
        console = System.out;
        System.setOut(new PrintStream(new Tee(console), true, StandardCharsets.UTF_8));
        context = new SpringApplicationBuilder(Application.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.application.name=tracing-probe", "--logging.structured.format.console=ecs",
                "--logging.level.root=WARN", "--logging.level.org.domainforge.tracing.internal=INFO");
        base = "http://127.0.0.1:" + context.getBean(Environment.class).getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        System.setOut(console);
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

    // ECS 结构化日志：每个请求一条 http_access，带服务名与响应头中的同一 traceId，不含查询参数。
    @Test
    void accessLogIsStructuredWithTraceId() throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/probe?secret=x")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        String line = awaitLine("\"traceId\":\"" + traceId + "\"");
        assertTrue(line.startsWith("{") && line.endsWith("}"), line);
        assertTrue(line.contains("http_access method=GET path=/probe status=200"), line);
        // Boot 的 ECS 格式为嵌套对象：服务名在 service.name，级别与日志器在 log 下，traceId 来自日志上下文。
        assertTrue(line.contains("\"service\":{\"name\":\"tracing-probe\""), "应带服务名：" + line);
        assertTrue(line.contains("\"log\":{\"level\":\"INFO\",\"logger\":"), "应带级别与日志器：" + line);
        assertTrue(line.contains("\"@timestamp\":"), "应带时间：" + line);
        assertTrue(!line.contains("secret"), "不应记录查询参数：" + line);
    }

    private static String awaitLine(String marker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> lines;
            synchronized (LOGS) {
                lines = Arrays.asList(LOGS.toString(StandardCharsets.UTF_8).split("\\R"));
            }
            for (String line : lines) if (line.contains(marker) && line.contains("http_access")) return line;
            Thread.sleep(50);
        }
        throw new AssertionError("未找到带 " + marker + " 的访问日志：" + LOGS.toString(StandardCharsets.UTF_8));
    }

    /**
     * 写入原控制台的同时留存一份。
     */
    private static final class Tee extends OutputStream {
        private final PrintStream target;

        Tee(PrintStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) {
            target.write(b);
            synchronized (LOGS) {
                LOGS.write(b);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            target.write(bytes, offset, length);
            synchronized (LOGS) {
                LOGS.write(bytes, offset, length);
            }
        }
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
