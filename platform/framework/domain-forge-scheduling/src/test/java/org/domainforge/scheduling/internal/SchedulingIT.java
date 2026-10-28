package org.domainforge.scheduling.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.domainforge.scheduling.api.ScheduledTask;
import org.domainforge.scheduling.api.TaskRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.powerjob.client.PowerJobClient;
import tech.powerjob.common.enums.InstanceStatus;
import tech.powerjob.common.request.query.InstancePageQuery;
import tech.powerjob.common.response.InstanceInfoDTO;
import tech.powerjob.common.response.JobInfoDTO;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PowerJob 服务端（PostgreSQL 存储）上的定时任务合同：启动时登记且不覆盖控制台修改、按周期执行、
 * 手动触发带参数、失败只记录不重试、上次未结束时跳过、暂停与恢复、执行记录可查。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchedulingIT {
    private static final String APP = "df-it-scheduling";
    private static final String APP_PASSWORD = "Scheduling-It-Pw1";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicInteger TICKS = new AtomicInteger();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final AtomicInteger SLOW_ACTIVE = new AtomicInteger();
    private static final AtomicInteger SLOW_MAX = new AtomicInteger();
    private static final AtomicInteger SLOW_RUNS = new AtomicInteger();
    private static final List<String> MANUAL = new CopyOnWriteArrayList<>();
    private static final Map<String, Long> IDS = new ConcurrentHashMap<>();
    private static String server;
    private static ConfigurableApplicationContext application;
    private static PowerJobClient client;

    @BeforeAll
    static void start() throws Exception {
        server = "127.0.0.1:" + env("POWERJOB_PORT");
        String token = console("/auth/thirdPartyLoginDirect", null,
                "{\"loginType\":\"PWJB\",\"originParams\":\"{\\\"username\\\":\\\"ADMIN\\\",\\\"password\\\":\\\""
                        + env("POWERJOB_ADMIN_PASSWORD") + "\\\"}\"}")
                .replaceAll(".*\"jwtToken\":\"([^\"]+)\".*", "$1");
        console("/namespace/save", token, "{\"code\":\"domainforge\",\"name\":\"domainforge\"}");
        String app = console("/appInfo/save", token, "{\"appName\":\"" + APP + "\",\"password\":\"" + APP_PASSWORD
                + "\",\"namespaceCode\":\"domainforge\"}");
        assertTrue(app.contains("\"success\":true") || app.contains("already exists"), app);
        // 服务端在容器中，经 host-gateway 连回宿主机上的 worker：worker 对外声明 host.docker.internal，且只在本测试期间
        // 监听所有网卡（默认只监听一块局域网网卡，容器经 host-gateway 进来的连接到不了；部署中 worker 与服务端同在 Compose 网络）。
        System.setProperty("powerjob.network.external.address", "host.docker.internal");
        System.setProperty("powerjob.network.local.address", "0.0.0.0");
        application = new SpringApplicationBuilder(Application.class).run(
                "--server.port=0",
                "--spring.application.name=" + APP,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--logging.level.tech.powerjob=INFO",
                "--domainforge.scheduling.enabled=true",
                "--domainforge.scheduling.server-address=" + server,
                "--domainforge.scheduling.app-password=" + APP_PASSWORD,
                "--domainforge.scheduling.worker-port=" + freePort());
        client = new PowerJobClient(server, APP, APP_PASSWORD);
        for (JobInfoDTO job : client.fetchAllJob().getData()) IDS.put(job.getJobName(), job.getId());
    }

    @AfterAll
    static void stop() throws Exception {
        System.clearProperty("powerjob.network.local.address");
        System.clearProperty("powerjob.network.external.address");
        if (application != null) application.close();
        if (client != null) client.close();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "需要真实 PowerJob 验证环境：缺少 " + name);
        return value;
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String console(String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + server + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Power_jwt", token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(500);
        }
        fail(timeout.toSeconds() + " 秒内未观察到：" + what);
    }

    private static List<InstanceInfoDTO> instances(String task) {
        var query = new InstancePageQuery();
        query.setJobIdEq(IDS.get(task));
        query.setPageSize(100);
        return client.queryInstanceInfo(query).getData().getData();
    }

    private static InstanceInfoDTO instance(long id) {
        return client.fetchInstanceInfo(id).getData();
    }

    private static double counter(String task, String result) {
        var found = application.getBean(MeterRegistry.class).find("domainforge.scheduling.run")
                .tag("task", task).tag("result", result).counter();
        return found == null ? 0 : found.count();
    }

    // 启动时登记全部任务，执行语义固定：单机、同一时刻最多一个实例、不重试、启用。
    @Test
    @Order(1)
    void tasksAreRegisteredWithFixedSemantics() {
        assertTrue(IDS.keySet().containsAll(List.of("it-tick", "it-fail", "it-slow", "it-manual")), IDS.toString());
        for (String task : List.of("it-tick", "it-fail", "it-slow", "it-manual")) {
            JobInfoDTO job = client.fetchJob(IDS.get(task)).getData();
            assertEquals(1, job.getMaxInstanceNum(), task);
            assertEquals(0, job.getInstanceRetryNum(), task);
            assertEquals(0, job.getTaskRetryNum(), task);
            assertEquals(TaskDispatcher.BEAN, job.getProcessorInfo(), task);
            assertEquals(task, job.getJobParams(), task);
        }
    }

    // 按周期执行，执行记录为成功，并计数。
    @Test
    @Order(2)
    void cronTriggersRunAndAreRecorded() throws Exception {
        try {
            await("周期任务执行两次", Duration.ofSeconds(60), () -> TICKS.get() >= 2);
        } catch (AssertionError missing) {
            instances("it-tick").forEach(item -> System.out.println("诊断：执行记录 status=" + item.getStatus()
                    + " worker=" + item.getTaskTrackerAddress() + " result=" + item.getResult()));
            throw missing;
        }
        await("执行记录为成功", Duration.ofSeconds(30), () -> instances("it-tick").stream()
                .anyMatch(item -> item.getStatus() == InstanceStatus.SUCCEED.getV()));
        assertTrue(counter("it-tick", "success") >= 2);
    }

    // 手动触发带参数；失败只记录一次，不自动重试，原因为异常类型。
    @Test
    @Order(3)
    void manualTriggerAndFailureWithoutRetry() throws Exception {
        long manual = client.runJob(IDS.get("it-manual"), "hello", 0).getData();
        await("手动触发执行", Duration.ofSeconds(30), () -> MANUAL.contains("hello"));
        await("手动触发记录为成功", Duration.ofSeconds(30),
                () -> instance(manual).getStatus() == InstanceStatus.SUCCEED.getV());
        long failed = client.runJob(IDS.get("it-fail"), "", 0).getData();
        await("失败被记录", Duration.ofSeconds(30),
                () -> instance(failed).getStatus() == InstanceStatus.FAILED.getV());
        assertTrue(instance(failed).getResult().contains("IllegalStateException"), instance(failed).getResult());
        Thread.sleep(5000);
        assertEquals(1, FAILURES.get(), "失败不应被自动重试");
        assertEquals(1, counter("it-fail", "failure"));
    }

    // 上次未结束时不开始新的执行：每 2 秒触发、每次执行 5 秒，执行从不并发，执行次数明显少于触发次数。
    @Test
    @Order(4)
    void overlappingTriggersAreSkipped() throws Exception {
        long started = System.nanoTime();
        int before = SLOW_RUNS.get();
        Thread.sleep(40_000);
        int runs = SLOW_RUNS.get() - before;
        long triggers = Duration.ofNanos(System.nanoTime() - started).toSeconds() / 2;
        assertTrue(runs >= 2, "应继续执行：" + runs);
        assertTrue(runs < triggers / 2, "未结束时的触发应被跳过：执行 " + runs + " 次，触发约 " + triggers + " 次");
        assertEquals(1, SLOW_MAX.get(), "同一任务不应并发执行");
    }

    // 控制台中暂停后不再执行，恢复后继续；控制台修改的周期在重新登记后保留。
    @Test
    @Order(5)
    void pauseResumeAndConsoleChangesAreKept() throws Exception {
        long tick = IDS.get("it-tick");
        client.disableJob(tick);
        Thread.sleep(4000);
        int paused = TICKS.get();
        Thread.sleep(8000);
        assertEquals(paused, TICKS.get(), "暂停后不应再执行");
        // 模拟控制台编辑：以完整字段保存，改周期并恢复启用。
        var changed = TaskRegistrar.request(application.getBean("tick", ScheduledTask.class));
        changed.setId(tick);
        changed.setTimeExpression("0/2 * * * * ?");
        assertTrue(client.saveJob(changed).isSuccess());
        await("恢复后继续执行", Duration.ofSeconds(30), () -> TICKS.get() > paused);
        assertEquals(java.util.Set.of(), new TaskRegistrar(application.getBeansOfType(ScheduledTask.class).values()
                .stream().toList(), client).register());
        assertEquals("0/2 * * * * ?", client.fetchJob(tick).getData().getTimeExpression());
    }

    /**
     * 自动配置＋四个测试任务。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ScheduledTask tick() {
            return task("it-tick", "0/3 * * * * ?", run -> TICKS.incrementAndGet());
        }

        @Bean
        ScheduledTask failing() {
            return task("it-fail", "0 0 0 1 1 ?", run -> {
                FAILURES.incrementAndGet();
                throw new IllegalStateException("预期的失败");
            });
        }

        @Bean
        ScheduledTask slow() {
            return task("it-slow", "0/2 * * * * ?", run -> {
                SLOW_RUNS.incrementAndGet();
                SLOW_MAX.accumulateAndGet(SLOW_ACTIVE.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(5000);
                } finally {
                    SLOW_ACTIVE.decrementAndGet();
                }
            });
        }

        @Bean
        ScheduledTask manual() {
            return task("it-manual", "0 0 0 1 1 ?", run -> MANUAL.add(run.parameters()));
        }

        private static ScheduledTask task(String name, String cron, Body body) {
            return new ScheduledTask() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public String cron() {
                    return cron;
                }

                @Override
                public String description() {
                    return "集成测试任务 " + name;
                }

                @Override
                public void run(TaskRun run) throws Exception {
                    body.run(run);
                }
            };
        }
    }

    /**
     * 测试任务的执行体。
     */
    @FunctionalInterface
    interface Body {
        void run(TaskRun run) throws Exception;
    }
}
