package com.company.leave.interfaces;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpLogic;
import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.bootstrap.LeaveServiceApplication;
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

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 与会话 Redis 上的请假服务：IAM 由本地 HTTP 替身按公开合同应答，验证每条业务规则、权限注解、
 * 部门范围、并发下余额不超扣与时段不重叠、依赖故障。
 */
class LeaveServiceIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> SUBJECTS = new ConcurrentHashMap<>();
    private static final Map<String, String> SCOPES = new ConcurrentHashMap<>();
    private static final AtomicBoolean IAM_DOWN = new AtomicBoolean();
    private static final String APPLY = LeavePermissions.APPLY;
    private static final String APPROVE = LeavePermissions.APPROVE;
    private static final String GRANT = LeavePermissions.GRANT;
    private static final String NONE = scope(false, false, "");
    private static final String ALL = scope(true, false, "");
    private static HttpServer iam;
    private static ConfigurableApplicationContext application;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        assertNotNull(System.getenv("POSTGRES_PORT"), "需要真实数据库验证环境");
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/access/", LeaveServiceIT::answer);
        iam.start();
        application = new SpringApplicationBuilder(LeaveServiceApplication.class).run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--management.server.port=0",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT")
                        + "/domainforge?currentSchema=leave_service",
                "--spring.datasource.username=domainforge",
                "--spring.datasource.password=" + env("POSTGRES_PASSWORD"),
                "--spring.flyway.schemas=leave_service",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + env("REDIS_PORT"),
                "--spring.data.redis.password=" + env("REDIS_PASSWORD"),
                "--leave-service.iam.url=http://127.0.0.1:" + iam.getAddress().getPort(),
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
        jdbc().update("TRUNCATE leave_request, leave_ledger");
        SUBJECTS.clear();
        SCOPES.clear();
        IAM_DOWN.set(false);
    }

    private static JdbcTemplate jdbc() {
        return application.getBean(JdbcTemplate.class);
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
        exchange.getResponseHeaders().add("Content-Type",
                status == 200 ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String scope(boolean all, boolean self, String units) {
        return "{\"all\":" + all + ",\"self\":" + self + ",\"unitIds\":[" + units + "]}";
    }

    private static String unit(long id) {
        return scope(false, false, "\"" + id + "\"");
    }

    /**
     * 在共享会话 Redis 中建立原生登录，并登记替身应答：三项权限各自的范围，NONE 表示没有该权限。
     */
    private static String user(long account, String apply, String approve, String grant) {
        String[] token = new String[1];
        StpLogic logic = application.getBean(StpLogic.class);
        SaTokenContextMockUtil.setMockContext(() -> {
            logic.login(account);
            token[0] = logic.getTokenValue();
        });
        List<String> granted = new ArrayList<>();
        if (!NONE.equals(apply)) granted.add("\"" + APPLY + "\"");
        if (!NONE.equals(approve)) granted.add("\"" + APPROVE + "\"");
        if (!NONE.equals(grant)) granted.add("\"" + GRANT + "\"");
        SUBJECTS.put(token[0], "{\"accountId\":\"" + account + "\",\"permissions\":[" + String.join(",", granted)
                + "],\"roles\":[]}");
        SCOPES.put(token[0], "{\"" + APPLY + "\":" + apply + ",\"" + APPROVE + "\":" + approve + ",\"" + GRANT + "\":"
                + grant + "}");
        return token[0];
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

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    // 失败统一为 Problem Details：状态码与扩展字段 code 都确定。
    private static void assertProblem(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertEquals(code, json(response).get("code").asString());
    }

    // 状态命令：携带读取时的申请版本。
    private static HttpResponse<String> act(String id, String command, String token, long version) throws Exception {
        return call("POST", "/api/leave-requests/" + id + "/" + command, token,
                "{\"expectedVersion\":" + version + "}");
    }

    private static HttpResponse<String> draft(String token, long unit, String start, String end) throws Exception {
        return call("POST", "/api/leave-requests", token,
                "{\"unitId\":\"" + unit + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}");
    }

    // 起草并提交，返回申请 ID。
    private static String pending(String token, long unit, String start, String end) throws Exception {
        var drafted = draft(token, unit, start, end);
        assertEquals(200, drafted.statusCode(), drafted.body());
        String id = json(drafted).get("id").asString();
        var submitted = act(id, "submit", token, 1);
        assertEquals("PENDING", json(submitted).get("status").asString(), submitted.body());
        return id;
    }

    private static void grant(long account, int days) throws Exception {
        String hr = user(900, NONE, NONE, ALL);
        var granted = call("POST", "/api/leave-balances/" + account + "/grants", hr, "{\"days\":" + days + "}");
        assertEquals(200, granted.statusCode(), granted.body());
    }

    private static int balance(String token) throws Exception {
        return json(call("GET", "/api/me/leave-balance", token, null)).get("remainingDays").asInt();
    }

    private static String status(String id) {
        return jdbc().queryForObject("SELECT status FROM leave_request WHERE id = ?", String.class, Long.parseLong(id));
    }

    @Test
    void missingTokenOrSameTokenIsUnauthenticated() throws Exception {
        String token = user(101, unit(10), NONE, NONE);
        assertEquals(401, call("GET", "/api/me/leave-balance", null, null).statusCode());
        assertEquals(401, send("GET", "/api/me/leave-balance", token, null, null).statusCode());
        assertEquals(401, send("GET", "/api/me/leave-balance", token, null, "forged").statusCode());
    }

    // 提交 → 审批通过 → 余额扣减；我的申请、待我审批与余额三个查询都反映结果。
    @Test
    void approvedRequestDeductsBalance() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String manager = user(201, NONE, unit(10), NONE);
        grant(101, 10);
        assertEquals(10, balance(alice));
        String id = pending(alice, 10, "2026-12-01", "2026-12-03");

        var queue = json(call("GET", "/api/leave-requests/pending", manager, null));
        assertEquals(1, queue.size());
        assertEquals(id, queue.get(0).get("id").asString());
        assertEquals("101", queue.get(0).get("applicantId").asString());
        assertEquals(3, queue.get(0).get("days").asInt());
        var approved = act(id, "approve", manager, 2);

        assertEquals("APPROVED", json(approved).get("status").asString(), approved.body());
        assertEquals(7, balance(alice));
        var mine = json(call("GET", "/api/me/leave-requests", alice, null));
        assertEquals(1, mine.size());
        assertEquals("APPROVED", mine.get(0).get("status").asString());
        assertEquals("2026-12-01", mine.get(0).get("startDate").asString());
        assertEquals(0, json(call("GET", "/api/leave-requests/pending", manager, null)).size());
        assertProblem(409, "LEAVE_STATE_CONFLICT",
                act(id, "withdraw", alice, 3));
    }

    // 提交 → 撤回：余额不变，撤回后不能再审批，时段被释放。驳回同样不动余额。
    @Test
    void withdrawnAndRejectedRequestsKeepBalance() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String manager = user(201, NONE, unit(10), NONE);
        grant(101, 10);
        String withdrawn = pending(alice, 10, "2026-12-01", "2026-12-03");

        assertEquals("WITHDRAWN", json(act(withdrawn, "withdraw", alice, 2))
                .get("status").asString());
        assertProblem(409, "LEAVE_STATE_CONFLICT",
                act(withdrawn, "approve", manager, 3));
        String rejected = pending(alice, 10, "2026-12-01", "2026-12-03");
        assertEquals("REJECTED", json(act(rejected, "reject", manager, 2))
                .get("status").asString());

        assertEquals(10, balance(alice));
        assertEquals(200, draft(alice, 10, "2026-12-02", "2026-12-02").statusCode());
    }

    // 时段与本人的草稿、待审或已通过申请重叠时拒绝；别人的申请不影响。
    @Test
    void overlappingPeriodIsRejected() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String bob = user(102, unit(10), NONE, NONE);
        pending(alice, 10, "2026-12-01", "2026-12-03");

        assertProblem(422, "LEAVE_PERIOD_OVERLAP", draft(alice, 10, "2026-12-03", "2026-12-05"));
        assertEquals(1, jdbc().queryForObject("SELECT count(*) FROM leave_request", Integer.class));
        assertEquals(200, draft(alice, 10, "2026-12-04", "2026-12-05").statusCode());
        assertEquals(200, draft(bob, 10, "2026-12-01", "2026-12-03").statusCode());
        assertProblem(400, "LEAVE_INVALID", draft(alice, 10, "2026-12-09", "2026-12-08"));
    }

    // 越权审批：范围外部门的申请按不存在处理，申请与余额都不变；没有审批权限 403；只能挂靠申请范围内的部门。
    @Test
    void approvalOutsideDepartmentScopeIsRejected() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String otherManager = user(202, NONE, unit(20), NONE);
        String colleague = user(103, unit(10), NONE, NONE);
        grant(101, 10);
        String id = pending(alice, 10, "2026-12-01", "2026-12-03");

        assertProblem(404, "LEAVE_REQUEST_NOT_FOUND",
                act(id, "approve", otherManager, 2));
        assertProblem(404, "LEAVE_REQUEST_NOT_FOUND",
                act(id, "reject", otherManager, 2));
        assertEquals(0, json(call("GET", "/api/leave-requests/pending", otherManager, null)).size());
        assertEquals(403, act(id, "approve", colleague, 2).statusCode());
        assertProblem(404, "LEAVE_REQUEST_NOT_FOUND",
                act(id, "withdraw", colleague, 2));
        assertEquals(403, draft(alice, 20, "2026-12-10", "2026-12-10").statusCode());
        assertEquals(403, call("POST", "/api/leave-balances/101/grants", alice, "{\"days\":5}").statusCode());

        assertEquals("PENDING", status(id));
        assertEquals(10, balance(alice));
    }

    @Test
    void approvalBeyondBalanceIsRejected() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String manager = user(201, NONE, ALL, NONE);
        grant(101, 2);
        String id = pending(alice, 10, "2026-12-01", "2026-12-03");

        assertProblem(422, "LEAVE_BALANCE_INSUFFICIENT",
                act(id, "approve", manager, 2));
        assertEquals("PENDING", status(id));
        assertEquals(2, balance(alice));
    }

    // 余额 5 天、两份各 3 天的待审申请被两位审批人同时通过：两个请求读到同一版本后都卡在账户行锁上，放行后只有一份通过，
    // 另一份 409；余额扣减 3 天，从不超扣。
    @Test
    void concurrentApprovalsNeverOverdrawBalance() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String first = user(201, NONE, unit(10), NONE);
        String second = user(202, NONE, unit(10), NONE);
        grant(101, 5);
        String a = pending(alice, 10, "2026-12-01", "2026-12-03");
        String b = pending(alice, 10, "2026-12-10", "2026-12-12");

        List<HttpResponse<String>> responses = raceBehindLedgerLock(101,
                () -> act(a, "approve", first, 2),
                () -> act(b, "approve", second, 2));

        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count());
        assertProblem(409, "VERSION_CONFLICT", responses.stream().filter(r -> r.statusCode() != 200).findFirst()
                .orElseThrow());
        assertEquals(1, jdbc().queryForObject("SELECT count(*) FROM leave_request WHERE status = 'APPROVED'",
                Integer.class));
        assertEquals(2, balance(alice));
    }

    // 同一人两份重叠的申请被同时起草：两个请求读到同一版本，放行后只有一份写入，另一份 409。
    @Test
    void concurrentOverlappingDraftsKeepOnlyOne() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        grant(101, 5);

        List<HttpResponse<String>> responses = raceBehindLedgerLock(101,
                () -> draft(alice, 10, "2026-12-01", "2026-12-03"),
                () -> draft(alice, 10, "2026-12-02", "2026-12-04"));

        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count());
        assertEquals(1, jdbc().queryForObject("SELECT count(*) FROM leave_request", Integer.class));
    }

    // 自批：申请人即使有全部审批范围，也不能通过或驳回自己的申请（403），申请与余额不变。
    @Test
    void applicantCannotDecideOwnRequest() throws Exception {
        String alice = user(101, unit(10), ALL, NONE);
        grant(101, 10);
        String id = pending(alice, 10, "2026-12-01", "2026-12-03");

        assertEquals(403, act(id, "approve", alice, 2).statusCode());
        assertEquals(403, act(id, "reject", alice, 2).statusCode());
        assertEquals(1, json(call("GET", "/api/leave-requests/pending", alice, null)).size());
        assertEquals("PENDING", status(id));
        assertEquals(10, balance(alice));
    }

    // 申请级版本：命令携带的版本与当前不符时 409 VERSION_CONFLICT，不改变申请；查询返回的版本可直接用于下一次命令。
    @Test
    void staleRequestVersionIsConflict() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String manager = user(201, NONE, unit(10), NONE);
        grant(101, 10);
        String id = pending(alice, 10, "2026-12-01", "2026-12-03");

        assertProblem(409, "VERSION_CONFLICT", act(id, "approve", manager, 1));
        assertProblem(409, "VERSION_CONFLICT", act(id, "reject", manager, 3));
        assertProblem(409, "VERSION_CONFLICT", act(id, "withdraw", alice, 1));
        assertEquals("PENDING", status(id));
        long current = json(call("GET", "/api/leave-requests/pending", manager, null)).get(0).get("version").asLong();
        var approved = act(id, "approve", manager, current);
        assertEquals(3, json(approved).get("version").asLong(), approved.body());
        assertEquals(7, balance(alice));
    }

    @Test
    void iamFailureIsDependencyUnavailable() throws Exception {
        String alice = user(101, unit(10), NONE, NONE);
        String manager = user(201, NONE, unit(10), NONE);
        String id = pending(alice, 10, "2026-12-01", "2026-12-03");
        IAM_DOWN.set(true);

        assertEquals(503, act(id, "approve", manager, 2).statusCode());
        assertEquals("PENDING", status(id));
    }

    /**
     * 让多个请求真正并发地修改同一账户：另一个事务先锁住账户行，请求照常读取（读不受行锁阻塞），都卡在条件更新上后再放行。
     */
    @SafeVarargs
    private static List<HttpResponse<String>> raceBehindLedgerLock(
            long account, Callable<HttpResponse<String>>... actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        try (Connection lock = application.getBean(DataSource.class).getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT 1 FROM leave_ledger WHERE account_id = ? FOR UPDATE")) {
                statement.setLong(1, account);
                statement.executeQuery().close();
            }
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (var action : actions) futures.add(pool.submit(action));
            awaitBlockedUpdates(actions.length);
            lock.commit();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (var future : futures) responses.add(future.get(30, TimeUnit.SECONDS));
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitBlockedUpdates(int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Integer blocked = jdbc().queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND query LIKE '%UPDATE leave_ledger%'", Integer.class);
            if (blocked != null && blocked == expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("请求没有在账户行锁上等待");
    }
}
