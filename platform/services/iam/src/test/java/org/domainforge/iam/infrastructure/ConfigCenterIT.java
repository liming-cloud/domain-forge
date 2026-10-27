package org.domainforge.iam.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.domainforge.iam.bootstrap.IamApplication;
import org.domainforge.iam.infrastructure.configuration.LoginProtectionSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 在开启鉴权的真实 Nacos 上验证配置中心合同（设计见 docs/architecture/05-配置中心.md）：
 * 启动读取、动态生效、无法应用的值保持当前值、运行中断开与恢复、启动时不可达或 dataId 缺失即失败。
 * 生效以登录防护的实际行为判定，不以收到通知为准。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigCenterIT {
    private static final String DATA_ID = "iam.yaml";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PROBE_LOGGER = "org.domainforge.iam.config.probe";
    private static String nacos;
    private static String token;
    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void prepare() throws Exception {
        assertNotNull(System.getenv("NACOS_PORT"), "需要真实 Nacos 验证环境");
        int http = Integer.parseInt(System.getenv("NACOS_PORT"));
        int grpc = Integer.parseInt(System.getenv("NACOS_GRPC_PORT"));
        // 随机端口映射下 gRPC 端口不等于 HTTP 端口 + 1000，按实际映射告知客户端偏移。
        System.setProperty("nacos.server.grpc.port.offset", Integer.toString(grpc - http));
        nacos = "http://127.0.0.1:" + http + "/nacos/v3";
        call("POST", "/auth/user/admin", Map.of("password", System.getenv("NACOS_ADMIN_PASSWORD")));
        login();
    }

    @AfterAll
    static void close() {
        if (application != null) application.close();
    }

    private static void login() throws Exception {
        String body = call("POST", "/auth/user/login",
                Map.of("username", "nacos", "password", System.getenv("NACOS_ADMIN_PASSWORD"))).body();
        token = body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private static HttpResponse<String> call(String method, String path, Map<String, String> form)
            throws Exception {
        String encoded = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        boolean query = method.equals("DELETE");
        var request = HttpRequest.newBuilder(URI.create(nacos + path + (query ? "?" + encoded : "")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, query ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(encoded));
        if (token != null) request.header("accessToken", token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void publish(int accountAttempts, String probeLevel) throws Exception {
        publishRaw(Integer.toString(accountAttempts), probeLevel);
    }

    private static void publishRaw(String accountAttempts, String probeLevel) throws Exception {
        String content = String.join("\n",
                "domainforge:",
                "  config:",
                "    source: nacos",
                "iam:",
                "  login-protection:",
                "    account-attempts: " + accountAttempts,
                "    account-window-seconds: 600",
                "    address-attempts: 30",
                "    address-window-seconds: 60",
                "logging:",
                "  level:",
                "    " + PROBE_LOGGER + ": " + probeLevel,
                "");
        var response = call("POST", "/admin/cs/config", Map.of(
                "dataId", DATA_ID, "groupName", "DEFAULT_GROUP", "content", content, "type", "yaml"));
        assertEquals(200, response.statusCode(), response.body());
        // 发布后客户端读取路径要数百毫秒才返回新内容；等到可读再继续，避免把读取时机当成被测行为。
        String marker = "account-attempts: " + accountAttempts;
        await("发布内容可读", () -> readable(marker));
    }

    private static boolean readable(String marker) {
        try {
            var query = HttpRequest.newBuilder(URI.create(
                            nacos + "/client/cs/config?dataId=" + DATA_ID + "&groupName=DEFAULT_GROUP"))
                    .header("accessToken", token).GET().build();
            return HTTP.send(query, HttpResponse.BodyHandlers.ofString()).body().contains(marker);
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(IamApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--spring.profiles.active=config-center",
                        "--IAM_NACOS_SERVER_ADDR=127.0.0.1:" + System.getenv("NACOS_PORT"),
                        "--IAM_NACOS_USERNAME=nacos",
                        "--IAM_NACOS_PASSWORD=" + System.getenv("NACOS_ADMIN_PASSWORD"));
    }

    private static <T> T bean(Class<T> type) {
        return application.getBean(type);
    }

    @SuppressWarnings("unchecked")
    private static LoginProtectionSettings limits() {
        return ((DynamicSettings<LoginProtectionSettings>) application.getBean("loginProtectionSettings")).current();
    }

    /**
     * 同一账号连续预占，返回第几次被限流；以 Redis 脚本的实际判定为准。
     */
    private static int limitedAt() {
        String name = "config." + UUID.randomUUID();
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                bean(LoginProtection.class).reserve(name, "127.0.0.20");
            } catch (IamException limited) {
                assertEquals(IamFailure.RATE_LIMITED, limited.failure());
                return attempt;
            }
        }
        return 0;
    }

    private static double rejected() {
        var counter = bean(MeterRegistry.class).find("domainforge.config.refresh")
                .tag("group", "iam.login-protection").tag("result", "rejected").counter();
        return counter == null ? 0 : counter.count();
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

    /**
     * 暂停而不是停止容器：随机端口映射在容器重启后会变化，暂停可模拟连接中断且保留端口。
     */
    private static void docker(String command) throws Exception {
        var process = new ProcessBuilder("docker", command, System.getenv("NACOS_CONTAINER"))
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor() == 0, "docker " + command + " 失败");
    }

    private static void awaitNacos() throws Exception {
        await("Nacos 恢复可用", Duration.ofSeconds(120), () -> {
            try {
                return call("GET", "/admin/core/state", Map.of()).statusCode() == 200;
            } catch (Exception unavailable) {
                return false;
            }
        });
        login();
    }

    // dataId 未发布时 Nacos 返回空内容而不报错；来源标记缺失必须阻止启动，不能以本地默认值运行。
    @Test
    @Order(1)
    void startupFailsWhenDataIdMissing() throws Exception {
        call("DELETE", "/admin/cs/config", Map.of("dataId", DATA_ID, "groupName", "DEFAULT_GROUP"));
        var failure = assertThrows(Exception.class, ConfigCenterIT::start);
        assertTrue(failure.toString().contains("domainforge.config.source")
                || String.valueOf(failure.getCause()).contains("domainforge.config.source"), failure.toString());
    }

    // 启动时读取 Nacos 中的阈值（YAML 默认是 5 次）。
    @Test
    @Order(2)
    void startupUsesPublishedValues() throws Exception {
        publish(2, "INFO");
        application = start();
        assertEquals(2, limits().accountAttempts());
        assertEquals(3, limitedAt());
    }

    // 运行中发布更严格的阈值，之后的登录预占按新阈值限流；日志级别同样实际改变。
    @Test
    @Order(3)
    void publishedChangesTakeEffect() throws Exception {
        assertTrue(!LoggerFactory.getLogger(PROBE_LOGGER).isDebugEnabled());
        publish(1, "DEBUG");
        await("新阈值生效", () -> limitedAt() == 2);
        assertEquals(1, limits().accountAttempts());
        await("日志级别生效", () -> LoggerFactory.getLogger(PROBE_LOGGER).isDebugEnabled());
    }

    // 越界与非数字的发布不生效，继续按当前阈值工作并计数；修正后的发布正常生效。
    @Test
    @Order(4)
    void unapplicableValuesKeepCurrentUntilCorrected() throws Exception {
        double before = rejected();
        publish(99, "DEBUG");
        await("越界值被拒绝", () -> rejected() == before + 1);
        publishRaw("many", "DEBUG");
        await("非数字被拒绝", () -> rejected() == before + 2);
        assertEquals(1, limits().accountAttempts());
        assertEquals(2, limitedAt());
        publish(3, "DEBUG");
        await("修正后生效", () -> limitedAt() == 4);
    }

    // 运行中 Nacos 断开：继续以当前值工作；恢复后新的发布照常生效。
    @Test
    @Order(5)
    void disconnectKeepsValuesAndRecovers() throws Exception {
        docker("pause");
        try {
            assertEquals(4, limitedAt());
        } finally {
            docker("unpause");
        }
        awaitNacos();
        publish(4, "DEBUG");
        await("恢复后新值生效", () -> limitedAt() == 5);
    }

    // 启动时 Nacos 不可达必须失败；此前已成功读取过同一 dataId，证明没有以客户端本地快照启动。
    @Test
    @Order(6)
    void startupFailsWhenNacosUnreachableEvenAfterPreviousRead() throws Exception {
        docker("pause");
        try {
            assertThrows(Exception.class, ConfigCenterIT::start);
        } finally {
            docker("unpause");
            awaitNacos();
        }
    }
}
