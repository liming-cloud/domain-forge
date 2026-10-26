package org.domainforge.iam.infrastructure;

import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.account.InitializeAdministrator;
import org.domainforge.iam.application.command.account.ManageAccount;
import org.domainforge.iam.application.command.authentication.ChangePassword;
import org.domainforge.iam.application.command.authentication.Login;
import org.domainforge.iam.application.command.authentication.Logout;
import org.domainforge.iam.application.command.registration.AcceptInvitation;
import org.domainforge.iam.application.command.registration.InvitationIssued;
import org.domainforge.iam.application.command.registration.IssueInvitation;
import org.domainforge.iam.application.command.role.ManageRole;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.bootstrap.IamApplication;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.role.DataScope;
import org.domainforge.iam.domain.model.role.PermissionGrant;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.infrastructure.persistence.fixture.IdentityFixtureMapper;
import org.domainforge.iam.infrastructure.persistence.retention.RetentionCleanup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 使用真实服务装配、Argon2、临时 PostgreSQL 与 Redis；不是替代数据库的模拟事务。
 */
class IdentityPersistenceIT {
    private static final String PASSWORD = "A private registration 密码 2026!";
    private static final String NEW_PASSWORD = "Another private 密码 for 2027!";
    private static final String ADMIN = "initial.admin";
    private static final String ADDRESS = "127.0.0.10";
    private static final Set<String> NO_INITIAL_ADMIN = Set.of(
            "concurrentInitializationCreatesOnlyOneAdministrator", "maintenanceCliStaysNonWebAndInitializes");
    private static ConfigurableApplicationContext application;
    private static IdentityFixtureMapper sql;

    // 外部环境提供临时数据库，缺失输入直接失败，不能跳过后显示绿灯。
    @BeforeAll
    static void start() {
        assertNotNull(System.getenv("IAM_DB_URL"), "需要真实数据库验证环境");
        application = new SpringApplicationBuilder(IamApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--logging.level.root=WARN");
        sql = application.getBean(IdentityFixtureMapper.class);
        assertEquals(1, application.getBeansOfType(StpLogic.class).size());
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
    }

    // 每个不变量独立建立业务状态；共享进程不共享账号、防护和审计前置。
    @BeforeEach
    void reset(TestInfo test) {
        sql.reset();
        bean(StringRedisTemplate.class).execute((RedisCallback<Void>) IdentityPersistenceIT::flush);
        if (!NO_INITIAL_ADMIN.contains(test.getTestMethod().orElseThrow().getName()))
            bean(InitializeAdministrator.class).execute(ADMIN, PASSWORD);
    }

    private static Void flush(RedisConnection connection) {
        connection.serverCommands().flushDb();
        return null;
    }

    private static <T> T bean(Class<T> type) {
        return application.getBean(type);
    }

    private static String login(String name, String password) {
        return bean(Login.class).execute(name, password, ADDRESS).token();
    }

    private static CurrentIdentity identity(String token) {
        return bean(CurrentAuthorization.class).current(token);
    }

    private static Account account(String name) {
        return bean(AccountRepository.class).findByLoginName(name);
    }

    private static InvitationIssued invite(String adminToken, String name) {
        return bean(IssueInvitation.class).execute(identity(adminToken).tokenDigest(), name, null);
    }

    private static long register(String adminToken, String name) {
        String secret = invite(adminToken, name).secret();
        return bean(AcceptInvitation.class).execute(secret, name, PASSWORD).targetId();
    }

    private static void grantAll(String adminToken, String name) {
        Account target = account(name);
        bean(ManageAccount.class).roles(
                identity(adminToken).tokenDigest(), target.id(), target.version(), Set.of(Role.SYSTEM_ADMIN_ID));
    }

    private static void setEnabled(String actorToken, String name, boolean enabled) {
        Account target = account(name);
        bean(ManageAccount.class).state(identity(actorToken).tokenDigest(), target.id(), target.version(), enabled);
    }

    private static void assertFailure(IamFailure expected, Executable command) {
        assertEquals(expected, assertThrows(IamException.class, command).failure());
    }

    private static void assertUnauthenticated(String token) {
        assertFailure(IamFailure.UNAUTHENTICATED, () -> identity(token));
    }

    /**
     * 两个命令同时起跑，返回各自结果；失败以异常对象返回，供调用方判定“恰好一个成功”。
     */
    private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> command : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return outcome(command);
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    private static Object outcome(Callable<?> command) {
        try {
            return command.call();
        } catch (Exception failure) {
            return failure;
        }
    }

    private static void assertOneSucceeded(List<Object> results, IamFailure loserFailure) {
        long failures = results.stream().filter(Exception.class::isInstance).count();
        assertEquals(1, failures, () -> "期望恰好一个失败: " + results);
        var loser = (Exception) results.stream().filter(Exception.class::isInstance).findFirst().orElseThrow();
        assertEquals(IamException.class, loser.getClass(), () -> "落败原因: " + loser);
        if (loserFailure != null) assertEquals(loserFailure, ((IamException) loser).failure());
    }

    private static void awaitAccountWait() throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (sql.waitingAccountLocks() > 0) return;
            Thread.sleep(25);
        }
        fail("没有观察到真实账号行锁等待");
    }

    /**
     * 用真实组件重新组装登录，替换会话端口以便控制提交时机或捕获已签发令牌。
     */
    private static Login loginUsing(NativeSessions nativeSessions, SessionStore sessions) {
        return new Login(
                bean(AccountRepository.class),
                bean(Passwords.class),
                bean(LoginProtection.class),
                nativeSessions,
                sessions,
                bean(SecretOperations.class),
                bean(TransactionOperations.class),
                bean(DatabaseTime.class));
    }

    /**
     * 登记许可后在仍持有账号行锁的事务内暂停，直到测试放行。
     */
    private static SessionStore pausedGrants(CountDownLatch inserted, CountDownLatch release) {
        SessionStore delegate = bean(SessionStore.class);
        return new SessionStore() {
            @Override
            public void insert(SessionGrant grant) {
                delegate.insert(grant);
                inserted.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }

            @Override
            public CurrentIdentity current(String tokenDigest) {
                return delegate.current(tokenDigest);
            }

            @Override
            public void revoke(String tokenDigest) {
                delegate.revoke(tokenDigest);
            }
        };
    }

    /**
     * 记录登录签发的原生令牌，其余行为委托真实实现。
     */
    private static NativeSessions capturing(AtomicReference<String> issued) {
        NativeSessions delegate = bean(NativeSessions.class);
        return new NativeSessions() {
            @Override
            public String issue(long accountId) {
                String token = delegate.issue(accountId);
                issued.set(token);
                return token;
            }

            @Override
            public Long subject(String token) {
                return delegate.subject(token);
            }

            @Override
            public void revoke(String token) {
                delegate.revoke(token);
            }

            @Override
            public void cleanup(String token) {
                delegate.cleanup(token);
            }
        };
    }

    // 同一实际数据库两条事务竞争初始化，只允许一个完整管理者，重复初始化被拒绝。
    @Test
    void concurrentInitializationCreatesOnlyOneAdministrator() throws Exception {
        var initializer = bean(InitializeAdministrator.class);
        var results = race(
                () -> initializer.execute("first.admin", PASSWORD),
                () -> initializer.execute("second.admin", PASSWORD));
        assertOneSucceeded(results, IamFailure.CONFLICT);
        assertEquals(1, sql.accountCount("first.admin") + sql.accountCount("second.admin"));
        assertEquals(1, sql.auditCount("administrator.initialize"));
        assertFailure(IamFailure.CONFLICT, () -> initializer.execute("third.admin", PASSWORD));
        assertEquals(1L, bean(AccountRepository.class).systemAdministrators());
    }

    // 邀请、注册、登录主路径，每个关键变更与审计同时提交。
    @Test
    void invitationRegistrationAndLoginWriteAudits() {
        String admin = login(ADMIN, PASSWORD);
        long member = register(admin, "new.member");
        CurrentIdentity current = identity(login("new.member", PASSWORD));
        assertEquals(member, current.accountId());
        assertTrue(current.permissions().isEmpty());
        assertEquals(1, sql.auditCount("administrator.initialize"));
        assertEquals(1, sql.auditCount("invitation.issue"));
        assertEquals(1, sql.auditCount("invitation.accept"));
    }

    // 同一邀请的并发接受只能消费一次。
    @Test
    void concurrentAcceptConsumesInvitationOnce() throws Exception {
        String secret = invite(login(ADMIN, PASSWORD), "race.member").secret();
        var accept = bean(AcceptInvitation.class);
        Callable<Object> command = () -> accept.execute(secret, "race.member", PASSWORD);
        var results = race(command, command);
        assertOneSucceeded(results, IamFailure.CONFLICT);
        assertEquals(1, sql.accountCount("race.member"));
        assertEquals(1, sql.auditCount("invitation.accept"));
    }

    // 过期、篡改、错绑登录名与格式错误的邀请都不能注册。
    @Test
    void expiredTamperedAndMisboundInvitationsReject() {
        String admin = login(ADMIN, PASSWORD);
        var accept = bean(AcceptInvitation.class);
        String secret = invite(admin, "bound.member").secret();
        assertFailure(IamFailure.CONFLICT, () -> accept.execute(secret, "other.member", PASSWORD));
        String tampered = (secret.charAt(0) == 'A' ? "B" : "A") + secret.substring(1);
        assertFailure(IamFailure.CONFLICT, () -> accept.execute(tampered, "bound.member", PASSWORD));
        assertFailure(IamFailure.INVALID_REQUEST, () -> accept.execute("short", "bound.member", PASSWORD));
        sql.expireInvitation("bound.member");
        assertFailure(IamFailure.CONFLICT, () -> accept.execute(secret, "bound.member", PASSWORD));
        assertEquals(0, sql.accountCount("bound.member"));
        assertEquals(0, sql.accountCount("other.member"));
    }

    // 审计写入失败使业务变更整体回滚，不存在“业务成功但审计缺失”。
    @Test
    void auditFailureRollsBackBusiness() {
        String admin = login(ADMIN, PASSWORD);
        String secret = invite(admin, "audit.member").secret();
        Account before = account(ADMIN);
        sql.installAuditFailure();
        try {
            assertThrows(RuntimeException.class, () -> bean(AcceptInvitation.class)
                    .execute(secret, "audit.member", PASSWORD));
            assertThrows(RuntimeException.class, () -> bean(ManageAccount.class)
                    .roles(identity(admin).tokenDigest(), before.id(), before.version(), Set.of()));
        } finally {
            sql.removeAuditFailure();
        }
        assertEquals(0, sql.accountCount("audit.member"));
        assertEquals(before.version(), account(ADMIN).version());
        assertEquals(before.roleIds(), account(ADMIN).roleIds());
        bean(AcceptInvitation.class).execute(secret, "audit.member", PASSWORD);
        assertEquals(1, sql.accountCount("audit.member"));
    }

    // 改密提升凭据版本，所有已签发会话失效，只能用新密码登录。
    @Test
    void passwordChangeInvalidatesAllSessions() {
        String first = login(ADMIN, PASSWORD);
        String second = login(ADMIN, PASSWORD);
        bean(ChangePassword.class).execute(identity(first), PASSWORD, NEW_PASSWORD);
        assertUnauthenticated(first);
        assertUnauthenticated(second);
        assertFailure(IamFailure.UNAUTHENTICATED, () -> login(ADMIN, PASSWORD));
        assertEquals(account(ADMIN).id(), identity(login(ADMIN, NEW_PASSWORD)).accountId());
        assertEquals(1, sql.auditCount("account.password"));
    }

    // 禁用使旧会话失效；重新启用不恢复旧会话，需要重新登录。
    @Test
    void disableInvalidatesSessionsAndReenableDoesNotRestore() {
        String admin = login(ADMIN, PASSWORD);
        register(admin, "state.member");
        String member = login("state.member", PASSWORD);
        setEnabled(admin, "state.member", false);
        assertUnauthenticated(member);
        assertFailure(IamFailure.UNAUTHENTICATED, () -> login("state.member", PASSWORD));
        setEnabled(admin, "state.member", true);
        assertUnauthenticated(member);
        assertNotNull(identity(login("state.member", PASSWORD)));
        assertEquals(2, sql.auditCount("account.state"));
    }

    // 退出只撤销当前会话，同账号其他会话不受影响。
    @Test
    void logoutRevokesOnlyCurrentSession() {
        String first = login(ADMIN, PASSWORD);
        String second = login(ADMIN, PASSWORD);
        bean(Logout.class).execute(identity(first), first);
        assertUnauthenticated(first);
        assertNotNull(identity(second));
        assertFailure(IamFailure.INVALID_REQUEST, () -> bean(Logout.class).execute(identity(second), first));
        assertEquals(1, sql.auditCount("session.logout"));
    }

    // 两个完整管理者并发互相禁用，管理守卫串行化后至少保留一个完整管理者。
    @Test
    void concurrentAdministratorsCannotRemoveLastOne() throws Exception {
        String admin = login(ADMIN, PASSWORD);
        register(admin, "second.admin");
        grantAll(admin, "second.admin");
        String second = login("second.admin", PASSWORD);
        var results = race(
                () -> {
                    setEnabled(admin, "second.admin", false);
                    return "first";
                },
                () -> {
                    setEnabled(second, ADMIN, false);
                    return "second";
                });
        assertOneSucceeded(results, null);
        assertEquals(1L, bean(AccountRepository.class).systemAdministrators());
    }

    // 非系统管理员即使持有账号状态权限，也不能修改系统管理员账号或分配角色；版本过期冲突，未变化不审计。
    @Test
    void ordinaryAdministratorCannotTouchSystemAdministrators() {
        String admin = login(ADMIN, PASSWORD);
        register(admin, "limited.admin");
        register(admin, "target.member");
        String adminDigest = identity(admin).tokenDigest();
        long role = bean(ManageRole.class).create(adminDigest, "ACCOUNT_STATE", "账号状态").targetId();
        bean(ManageRole.class).grants(adminDigest, role, 1, Set.of(
                new PermissionGrant("iam:account:state", DataScope.NOT_APPLICABLE, Set.of())));
        Account limited = account("limited.admin");
        var manage = bean(ManageAccount.class);
        manage.roles(adminDigest, limited.id(), limited.version(), Set.of(role));
        String digest = identity(login("limited.admin", PASSWORD)).tokenDigest();
        Account system = account(ADMIN);
        assertFailure(IamFailure.FORBIDDEN, () -> manage.state(digest, system.id(), system.version(), false));
        Account target = account("target.member");
        assertFailure(IamFailure.FORBIDDEN, () -> manage.roles(digest, target.id(), target.version(), Set.of(role)));
        assertFailure(IamFailure.CONFLICT, () -> manage.state(digest, target.id(), target.version() + 1, false));
        int audits = sql.auditCount("account.state");
        assertEquals(target.version(), manage.state(digest, target.id(), target.version(), true).version());
        assertEquals(audits, sql.auditCount("account.state"));
        assertEquals(target.version() + 1, manage.state(digest, target.id(), target.version(), false).version());
    }

    // 连续失败达到账号额度后，即使密码正确也在窗口内被限流。
    @Test
    void loginRateLimitedAfterFailures() {
        for (int attempt = 0; attempt < 5; attempt++)
            assertFailure(IamFailure.UNAUTHENTICATED, () -> login(ADMIN, NEW_PASSWORD));
        IamException limited = assertThrows(IamException.class, () -> login(ADMIN, PASSWORD));
        assertEquals(IamFailure.RATE_LIMITED, limited.failure());
    }

    // 许可提交失败时不返回令牌，原生会话被清理，SQL 中没有许可。
    @Test
    void loginCommitFailureDoesNotRegisterToken() {
        var issued = new AtomicReference<String>();
        Login login = loginUsing(capturing(issued), bean(SessionStore.class));
        sql.installGrantCommitFailure();
        try {
            assertThrows(RuntimeException.class, () -> login.execute(ADMIN, PASSWORD, ADDRESS));
        } finally {
            sql.removeGrantCommitFailure();
        }
        assertNotNull(issued.get());
        assertNull(bean(NativeSessions.class).subject(issued.get()));
        assertEquals(0, sql.sessionCount(bean(SecretOperations.class).digest(issued.get())));
    }

    // 登录在密码校验后、许可提交前持有账号锁；等待中的改密在其后提交，新许可随即失效。
    @Test
    void passwordChangeWaitingForLoginInvalidatesCommittedGrant() throws Exception {
        CurrentIdentity actor = identity(login(ADMIN, PASSWORD));
        changeWaitingForLogin(ADMIN, () -> bean(ChangePassword.class).execute(actor, PASSWORD, NEW_PASSWORD));
    }

    // 同上，等待中的禁用在登录提交后生效。
    @Test
    void disableWaitingForLoginInvalidatesCommittedGrant() throws Exception {
        String admin = login(ADMIN, PASSWORD);
        register(admin, "locked.member");
        changeWaitingForLogin("locked.member", () -> {
            setEnabled(admin, "locked.member", false);
            return null;
        });
    }

    private void changeWaitingForLogin(String name, Callable<?> change) throws Exception {
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var issued = new AtomicReference<String>();
        Login paused = loginUsing(capturing(issued), pausedGrants(inserted, release));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> login = pool.submit(() -> paused.execute(name, PASSWORD, ADDRESS));
            assertTrue(inserted.await(10, TimeUnit.SECONDS));
            Future<?> changed = pool.submit(change);
            awaitAccountWait();
            release.countDown();
            login.get(20, TimeUnit.SECONDS);
            changed.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, sql.sessionCount(bean(SecretOperations.class).digest(issued.get())));
        assertUnauthenticated(issued.get());
    }

    // 重发与接受竞争同一原邀请，只有一个能处理原邀请。
    @Test
    void reissueAndAcceptanceCannotBothConsumeOriginal() throws Exception {
        String admin = login(ADMIN, PASSWORD);
        InvitationIssued original = invite(admin, "reissue.member");
        String digest = identity(admin).tokenDigest();
        var results = race(
                () -> bean(IssueInvitation.class).execute(digest, "reissue.member", original.invitationId()),
                () -> bean(AcceptInvitation.class).execute(original.secret(), "reissue.member", PASSWORD));
        assertOneSucceeded(results, IamFailure.CONFLICT);
        boolean accepted = !(results.get(1) instanceof Exception);
        assertEquals(accepted ? 1 : 0, sql.accountCount("reissue.member"));
        assertEquals(accepted ? 1 : 2, sql.auditCount("invitation.issue"));
    }

    // 保留期清理每批有界，过期审计分批删除，新审计保留。
    @Test
    void retentionDeletesInBoundedBatches() {
        sql.insertExpiredAudits(1005);
        var cleanup = bean(RetentionCleanup.class);
        assertEquals(1000, cleanup.run());
        assertEquals(5, cleanup.run());
        assertEquals(0, cleanup.run());
        assertEquals(0, sql.auditCount("retention.old"));
        assertEquals(1, sql.auditCount("administrator.initialize"));
    }

    // 维护命令不能被部署属性改成 Web 模式；密码文件仅属主可读时初始化成功。
    @Test
    void maintenanceCliStaysNonWebAndInitializes() throws Exception {
        Path password = Files.createTempFile("iam-admin-", ".secret");
        Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(password, PASSWORD);
        Path log = Files.createTempFile("iam-maintenance-", ".log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dspring.main.web-application-type=servlet",
                "-Dserver.port=invalid-maintenance-port",
                "-cp",
                System.getProperty("java.class.path"),
                IamApplication.class.getName(),
                "initialize-admin",
                "--login-name=cli.admin",
                "--password-file=" + password)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI 结束超时: " + log);
            assertEquals(0, process.exitValue(), "非 Web 维护被部署属性覆盖: " + log);
            assertTrue(Files.readString(log).contains("administrator_initialized accountId="));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            Files.deleteIfExists(password);
        }
        assertEquals(1, sql.accountCount("cli.admin"));
        assertNotEquals(0L, account("cli.admin").id());
    }
}
