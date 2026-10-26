package org.domainforge.iam.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.iam.application.command.account.InitializeAdministrator;
import org.domainforge.iam.application.command.authentication.MaintainLoginPolicy;
import org.domainforge.iam.application.command.registration.AcceptInvitation;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.LoginProtection;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.bootstrap.IamApplication;
import org.domainforge.iam.domain.model.RegistrationInvitation;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.domainforge.iam.infrastructure.configuration.LoginProtectionSettings;
import org.domainforge.iam.infrastructure.persistence.fixture.IdentityFixtureMapper;
import org.domainforge.iam.infrastructure.persistence.session.ProtectionMapper;
import org.domainforge.iam.infrastructure.security.RedisLoginProtection;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实服务装配、Argon2及临时PostgreSQL；不是替代数据库的模拟事务。
 */
class IdentityPersistenceIT {
    private static final String PASSWORD = "A private registration 密码 2026!";
    private static ConfigurableApplicationContext application;
    private static IdentityFixtureMapper sql;
    private static UUID administrator;

    // 外部脚本提供临时数据库，缺失输入直接失败，不能跳过后显示绿灯。
    @BeforeAll
    static void start() {
        assertNotNull(System.getenv("IAM_DB_URL"), "运行真实数据库验证脚本");
        application =
                new SpringApplicationBuilder(IamApplication.class)
                        .web(WebApplicationType.NONE)
                        .run("--spring.main.banner-mode=off", "--logging.level.root=WARN");
        sql = application.getBean(IdentityFixtureMapper.class);
        var logic = application.getBean(cn.dev33.satoken.stp.StpLogic.class);
        assertEquals(cn.dev33.satoken.stp.StpLogic.class, logic.getClass());
        assertEquals(1, application.getBeansOfType(cn.dev33.satoken.stp.StpLogic.class).size());
        assertSame(logic, cn.dev33.satoken.stp.StpUtil.getStpLogic());
        assertTrue(
                application.getBeansOfType(org.domainforge.access.api.CurrentAccess.class).isEmpty());
    }

    @AfterAll
    static void stop() {
        if (application != null) application.close();
    }

    private static void awaitAccountWait() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (sql.waitingAccountLocks() > 0) return;
            Thread.sleep(25);
        }
        fail("没有观察到真实账号行锁等待");
    }

    private static org.domainforge.iam.application.port.authentication.SessionStore pausedGrants(
            java.util.concurrent.CountDownLatch inserted, java.util.concurrent.CountDownLatch release) {
        var delegate =
                application.getBean(org.domainforge.iam.application.port.authentication.SessionStore.class);
        return new org.domainforge.iam.application.port.authentication.SessionStore() {
            @Override
            public void insert(org.domainforge.iam.application.port.authentication.SessionGrant grant) {
                delegate.insert(grant);
                inserted.countDown();
                try {
                    assertTrue(release.await(8, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }

            @Override
            public org.domainforge.iam.application.port.authentication.CurrentIdentity current(
                    String digest) {
                return delegate.current(digest);
            }

            @Override
            public void revoke(String digest) {
                delegate.revoke(digest);
            }
        };
    }

    private static UUID registerRaceAccount(String name) {
        return application
                .getBean(AcceptInvitation.class)
                .execute(UUID.randomUUID(), invite(name), name, PASSWORD)
                .targetId();
    }

    private static org.domainforge.iam.application.command.authentication.Login loginUsing(
            org.domainforge.iam.application.port.authentication.NativeSessions nativeSessions,
            org.domainforge.iam.application.port.authentication.SessionStore sessions) {
        return new org.domainforge.iam.application.command.authentication.Login(
                application.getBean(AccountRepository.class),
                application.getBean(org.domainforge.iam.application.port.authentication.Passwords.class),
                application.getBean(
                        org.domainforge.iam.application.port.authentication.LoginProtection.class),
                nativeSessions,
                sessions,
                application.getBean(SecretOperations.class),
                application.getBean(Transactions.class),
                application.getBean(org.domainforge.iam.application.port.operation.DatabaseTime.class));
    }

    private static org.domainforge.iam.application.command.registration.IssueInvitation issueUsing(
            Transactions transactions, org.domainforge.iam.application.port.audit.AuditTrail audit) {
        return new org.domainforge.iam.application.command.registration.IssueInvitation(
                transactions,
                application.getBean(org.domainforge.iam.application.port.operation.DatabaseTime.class),
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class),
                application.getBean(AccountRepository.class),
                application.getBean(InvitationRepository.class),
                application.getBean(org.domainforge.iam.application.port.operation.CommandReceipts.class),
                application.getBean(SecretOperations.class),
                audit);
    }

    private static boolean disable(
            org.domainforge.iam.application.command.account.ManageAccount manage,
            String digest,
            UUID account,
            long version) {
        try {
            manage.state(digest, UUID.randomUUID(), account, version, false);
            return true;
        } catch (IamException error) {
            assertEquals(IamFailure.CONFLICT, error.failure());
            return false;
        }
    }

    // 此处仅预置有效邀请以验证注册事务，管理邀请入口另用真实授权场景验证。
    private static String invite(String name) {
        var secrets = application.getBean(SecretOperations.class);
        String secret = secrets.newSecret();
        application
                .getBean(Transactions.class)
                .execute(
                        () -> {
                            var expires =
                                    application
                                            .getBean(org.domainforge.iam.application.port.operation.DatabaseTime.class)
                                            .now()
                                            .plus(Duration.ofHours(24));
                            application
                                    .getBean(InvitationRepository.class)
                                    .insert(
                                            new RegistrationInvitation(
                                                    UUID.randomUUID(),
                                                    name,
                                                    secrets.digest(secret),
                                                    expires,
                                                    administrator,
                                                    null,
                                                    false));
                            return null;
                        });
        return secret;
    }

    private static CommandResult attempt(
            AcceptInvitation accept, UUID operation, String secret, String name) {
        try {
            return accept.execute(operation, secret, name, PASSWORD);
        } catch (IamException e) {
            assertEquals(IamFailure.CONFLICT, e.failure());
            return null;
        }
    }

    private static RedisLoginProtection protection(LoginProtectionSettings settings) {
        return new RedisLoginProtection(
                application.getBean(StringRedisTemplate.class),
                application.getBean(ProtectionMapper.class),
                application.getBean(Transactions.class),
                application.getBean(SecretOperations.class),
                settings);
    }

    // 每个不变量独立建立业务状态；共享进程不共享账号、收据、防护和审计前置。
    @BeforeEach
    void reset(TestInfo test) {
        sql.reset();
        application
                .getBean(org.springframework.data.redis.core.StringRedisTemplate.class)
                .execute(
                        (org.springframework.data.redis.core.RedisCallback<Void>)
                                connection -> {
                                    connection.serverCommands().flushDb();
                                    return null;
                                });
        administrator = null;
        if (!test.getTestMethod()
                .orElseThrow()
                .getName()
                .equals("concurrentInitializationCreatesOnlyOneAdministrator")) {
            administrator =
                    application
                            .getBean(InitializeAdministrator.class)
                            .execute("initial.admin", PASSWORD)
                            .targetId();
        }
    }

    // 同一实际数据库两条事务竞争初始化，只允许一个完整管理者且重复不重置密码。
    @Test
    void concurrentInitializationCreatesOnlyOneAdministrator() throws Exception {
        var initializer = application.getBean(InitializeAdministrator.class);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> operation =
                    () -> {
                        try {
                            initializer.execute("initial.admin", PASSWORD);
                            return true;
                        } catch (IamException e) {
                            assertEquals(IamFailure.CONFLICT, e.failure());
                            return false;
                        }
                    };
            var results = pool.invokeAll(List.of(operation, operation));
            assertEquals(
                    1,
                    results.stream()
                            .filter(
                                    result -> {
                                        try {
                                            return result.get();
                                        } catch (Exception e) {
                                            throw new AssertionError(e);
                                        }
                                    })
                            .count());
        }
        var account = application.getBean(AccountRepository.class).findByLoginName("initial.admin");
        administrator = account.id();
        assertTrue(account.isCompleteAdministrator());
        assertEquals(1, sql.accountCount());
        assertEquals(1, sql.initializationAuditCount());
        assertThrows(IamException.class, () -> initializer.execute("another.admin", PASSWORD));
        assertEquals(account, application.getBean(AccountRepository.class).find(administrator));
    }

    // 两个并发注册竞争同一邀请，仅一账号可提交；成功重放返回同一个结果。
    @Test
    void concurrentAcceptAndReceiptAreAtomic() throws Exception {
        String secret = invite("parallel.user");
        var accept = application.getBean(AcceptInvitation.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<CommandResult> one = () -> attempt(accept, first, secret, "parallel.user");
            Callable<CommandResult> two = () -> attempt(accept, second, secret, "parallel.user");
            var results = pool.invokeAll(List.of(one, two));
            var a = results.get(0).get();
            var b = results.get(1).get();
            assertTrue((a == null) != (b == null));
            var success = a == null ? b : a;
            assertEquals(
                    success, accept.execute(success.operationId(), secret, "PARALLEL.USER", PASSWORD));
            assertThrows(
                    IamException.class,
                    () ->
                            accept.execute(success.operationId(), secret, "parallel.user", PASSWORD + "changed"));
        }
        var account = application.getBean(AccountRepository.class).findByLoginName("parallel.user");
        assertTrue(account.permissions().isEmpty());
        assertEquals(1, sql.parallelAccountCount());
        assertEquals(1, sql.registrationReceiptCount());
    }

    // 在建号之后强制邀请保存失败，三项状态全部回滚，恢复后可用同操作ID重试。
    @Test
    void laterSqlFailureRollsBackAccountInvitationAndReceipt() {
        String secret = invite("rollback.user");
        UUID operation = UUID.randomUUID();
        sql.createInvitationFailure();
        sql.attachInvitationFailure();
        try {
            var failure =
                    assertThrows(
                            IamException.class,
                            () ->
                                    application
                                            .getBean(AcceptInvitation.class)
                                            .execute(operation, secret, "rollback.user", PASSWORD));
            assertEquals(IamFailure.UNAVAILABLE, failure.failure());
            assertEquals(0, sql.rollbackAccountCount());
            assertEquals(0, sql.operationCount(operation.toString()));
            assertEquals(1, sql.unconsumedRollbackInvitationCount());
        } finally {
            sql.removeInvitationFailure();
            sql.dropInvitationFailure();
        }
        assertNotNull(
                application
                        .getBean(AcceptInvitation.class)
                        .execute(operation, secret, "rollback.user", PASSWORD));
    }

    // 精确到期、篡改、错误登录名均拒绝，不借重复请求或失败审计创建额外账号。
    @Test
    void expiredTamperedAndMisboundInvitationsReject() {
        String secret = invite("expiry.user");
        sql.expireInvitation();
        var accept = application.getBean(AcceptInvitation.class);
        assertThrows(
                IamException.class,
                () -> accept.execute(UUID.randomUUID(), secret, "expiry.user", PASSWORD));
        assertThrows(
                IamException.class,
                () -> accept.execute(UUID.randomUUID(), secret, "someone.else", PASSWORD));
        assertThrows(
                IamException.class,
                () -> accept.execute(UUID.randomUUID(), "A".repeat(43), "expiry.user", PASSWORD));
        assertEquals(0, sql.expiredAccountCount());
    }

    // 单独审计表故障不回滚已完成注册，但独立缺口计数递增；恢复后正常审计可继续。
    @Test
    void auditFailureKeepsBusinessAndReportsGap() {
        String secret = invite("audit.gap");
        var registry = application.getBean(MeterRegistry.class);
        double before = registry.get("iam.audit.incomplete").counter().count();
        sql.createAuditFailure();
        sql.attachAuditFailure();
        try {
            var result =
                    application
                            .getBean(AcceptInvitation.class)
                            .execute(UUID.randomUUID(), secret, "audit.gap", PASSWORD);
            assertNotNull(application.getBean(AccountRepository.class).find(result.targetId()));
            assertEquals(before + 1, registry.get("iam.audit.incomplete").counter().count());
            assertEquals(0, sql.targetAuditCount(result.targetId().toString()));
        } finally {
            sql.removeAuditFailure();
            sql.dropAuditFailure();
        }
        String recovered = invite("audit.recovered");
        var result =
                application
                        .getBean(AcceptInvitation.class)
                        .execute(UUID.randomUUID(), recovered, "audit.recovered", PASSWORD);
        assertEquals(1, sql.targetAuditCount(result.targetId().toString()));
    }

    // 补录不冒充原始成功：真实写入来源、未知字段和当前补录者；幂等不新增行。
    @Test
    void manualBackfillAndResultLookupKeepUnknownFacts() {
        var login =
                application
                        .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                        .execute("initial.admin", PASSWORD, "127.0.0.6");
        var current =
                application
                        .getBean(org.domainforge.iam.application.authorization.CurrentAuthorization.class)
                        .current(login.token());
        var entry =
                new org.domainforge.iam.application.port.audit.ManualAudit(
                        UUID.randomUUID(),
                        administrator,
                        "invitation.accept",
                        null,
                        "ticket:IAM-6",
                        java.util.Set.of("occurredAt", "originalActor", "originalOutcome"));
        var backfill =
                application.getBean(org.domainforge.iam.application.command.audit.BackfillAudit.class);
        UUID op = UUID.randomUUID();
        var result = backfill.execute(current.tokenDigest(), op, entry);
        assertEquals(result, backfill.execute(current.tokenDigest(), op, entry));
        var saved = sql.manualAudit(entry.originalOperationId().toString());
        assertEquals(administrator.toString(), saved.get("actor"));
        assertEquals(null, saved.get("occurred_at"));
        assertEquals("original-result-unconfirmed", saved.get("outcome"));
        assertEquals("ticket:IAM-6", saved.get("provenance"));
        var observe =
                application.getBean(org.domainforge.iam.application.query.operation.ObserveOperation.class);
        assertEquals(
                result, observe.execute(current.accountId(), current.permissions(), op, null).result());
        assertFalse(
                observe
                        .execute(current.accountId(), current.permissions(), UUID.randomUUID(), null)
                        .confirmed());
        assertThrows(
                IamException.class,
                () -> observe.execute(UUID.randomUUID(), java.util.Set.of(), op, administrator));
        assertThrows(
                IamException.class, () -> observe.execute(administrator, java.util.Set.of(), op, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new org.domainforge.iam.application.port.audit.ManualAudit(
                                UUID.randomUUID(),
                                administrator,
                                "invitation.accept",
                                null,
                                "raw-secret-text",
                                entry.missingFields()));
    }

    // 清理每批有界；刚到期邀请仍保留供24小时收据重放，人工补录按记录时间保留。
    @Test
    void retentionIsBoundedAndPreservesReplayWindow() {
        sql.manualRetentionRecord();
        sql.expiredReceipts();
        String secret = invite("cleanup.replay");
        sql.expireReplayInvitation();
        sql.expiredAudits();
        var cleanup =
                application.getBean(
                        org.domainforge.iam.infrastructure.persistence.retention.RetentionCleanup.class);
        assertEquals(2000, cleanup.run());
        assertEquals(5, sql.cleanupReceiptCount());
        assertEquals(5, sql.cleanupAuditCount());
        assertEquals(1, sql.replayInvitationCount());
        assertEquals(1, sql.manualAuditCount());
        assertEquals(10, cleanup.run());
        assertEquals(0, cleanup.run());
    }

    // 真实旧快照不能成为身份允许的来源，即使调用者显式开启REPEATABLE_READ也应拒绝。
    @Test
    void oldSnapshotCannotAuthorize() {
        var login =
                application
                        .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                        .execute("initial.admin", PASSWORD, "127.0.0.8");
        String digest = application.getBean(SecretOperations.class).digest(login.token());
        var store =
                application.getBean(org.domainforge.iam.application.port.authentication.SessionStore.class);
        assertNotNull(store.current(digest));
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        application.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(
                status -> {
                    sql.sessionCount();
                    application
                            .getBean(Transactions.class)
                            .execute(
                                    () -> {
                                        store.revoke(digest);
                                        return null;
                                    });
                    var error = assertThrows(IamException.class, () -> store.current(digest));
                    assertEquals(IamFailure.UNAVAILABLE, error.failure());
                    return null;
                });
        assertEquals(null, store.current(digest));
    }

    // 两名完整管理者同时禁用自己：真实守卫与账号事务只允许一方成功，另一方仍可管理。
    @Test
    void concurrentAdministratorsCannotRemoveLastOne() throws Exception {
        var accept = application.getBean(AcceptInvitation.class);
        var login =
                application.getBean(org.domainforge.iam.application.command.authentication.Login.class);
        UUID second =
                accept
                        .execute(UUID.randomUUID(), invite("second.admin"), "second.admin", PASSWORD)
                        .targetId();
        var auth =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        String firstToken = login.execute("initial.admin", PASSWORD, "127.0.0.9").token();
        String firstDigest = auth.current(firstToken).tokenDigest();
        var manage =
                application.getBean(org.domainforge.iam.application.command.account.ManageAccount.class);
        var granted =
                manage.permissions(
                        firstDigest,
                        UUID.randomUUID(),
                        second,
                        1,
                        org.domainforge.iam.domain.model.Account.MANAGEMENT_PERMISSIONS);
        String secondToken = login.execute("second.admin", PASSWORD, "127.0.0.10").token();
        String secondDigest = auth.current(secondToken).tokenDigest();
        var accounts = application.getBean(AccountRepository.class);
        long firstVersion = accounts.find(administrator).version();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = () -> disable(manage, firstDigest, administrator, firstVersion);
            Callable<Boolean> other = () -> disable(manage, secondDigest, second, granted.version());
            var outcomes = pool.invokeAll(List.of(first, other));
            assertTrue(outcomes.get(0).get() != outcomes.get(1).get());
        }
        assertEquals(1, accounts.completeAdministrators());
        boolean firstEnabled = accounts.find(administrator).enabled();
        UUID disabled = firstEnabled ? second : administrator;
        String active = firstEnabled ? firstDigest : secondDigest;
        manage.state(active, UUID.randomUUID(), disabled, accounts.find(disabled).version(), true);
        assertEquals(2, accounts.completeAdministrators());
        assertThrows(IamException.class, () -> auth.current(firstEnabled ? secondToken : firstToken));
    }

    // Redis签发与SQL登记之间发生改密：暂停真实签发后让改密提交，旧密码登录不得交付许可。
    @Test
    void passwordRaceRejectsAlreadyIssuedRedisToken() throws Exception {
        UUID account =
                application
                        .getBean(AcceptInvitation.class)
                        .execute(UUID.randomUUID(), invite("race.password"), "race.password", PASSWORD)
                        .targetId();
        var original =
                application
                        .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                        .execute("race.password", PASSWORD, "127.0.0.11");
        var auth =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        String digest = auth.current(original.token()).tokenDigest();
        var paused =
                new PausedNativeSessions(
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.NativeSessions.class));
        var login =
                new org.domainforge.iam.application.command.authentication.Login(
                        application.getBean(AccountRepository.class),
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.Passwords.class),
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.LoginProtection.class),
                        paused,
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.SessionStore.class),
                        application.getBean(SecretOperations.class),
                        application.getBean(Transactions.class),
                        application.getBean(org.domainforge.iam.application.port.operation.DatabaseTime.class));
        try (var pool = Executors.newFixedThreadPool(1)) {
            var future = pool.submit(() -> login.execute("race.password", PASSWORD, "127.0.0.12"));
            assertTrue(paused.issued.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                application
                        .getBean(org.domainforge.iam.application.command.authentication.ChangePassword.class)
                        .execute(
                                auth.current(original.token()), UUID.randomUUID(), PASSWORD, PASSWORD + "changed");
            } finally {
                paused.resume.countDown();
            }
            var failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(IamFailure.UNAUTHENTICATED, ((IamException) failure.getCause()).failure());
        }
        assertEquals(
                0, sql.tokenSessionCount(application.getBean(SecretOperations.class).digest(paused.token)));
        assertThrows(IamException.class, () -> auth.current(paused.token));
        assertThrows(IamException.class, () -> auth.current(original.token()));
        assertEquals(2, application.getBean(AccountRepository.class).find(account).credentialVersion());
    }

    // 实际 Login 在原生签发后、SQL 之前遇到禁用，不能给旧凭据登记许可或返回令牌。
    @Test
    void disableRaceRejectsAlreadyIssuedRedisToken() throws Exception {
        UUID account = registerRaceAccount("race.disabled");
        var auth =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        String admin =
                auth.current(
                                application
                                        .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                                        .execute("initial.admin", PASSWORD, "127.0.0.31")
                                        .token())
                        .tokenDigest();
        var paused =
                new PausedNativeSessions(
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.NativeSessions.class));
        var login =
                loginUsing(
                        paused,
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.SessionStore.class));
        try (var pool = Executors.newFixedThreadPool(1)) {
            var future = pool.submit(() -> login.execute("race.disabled", PASSWORD, "127.0.0.32"));
            assertTrue(paused.issued.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                application
                        .getBean(org.domainforge.iam.application.command.account.ManageAccount.class)
                        .state(admin, UUID.randomUUID(), account, 1, false);
            } finally {
                paused.resume.countDown();
            }
            var failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(IamFailure.UNAUTHENTICATED, ((IamException) failure.getCause()).failure());
        }
        assertEquals(
                0, sql.tokenSessionCount(application.getBean(SecretOperations.class).digest(paused.token)));
        assertThrows(IamException.class, () -> auth.current(paused.token));
        assertFalse(application.getBean(AccountRepository.class).find(account).enabled());
    }

    // 反向交错：Login 真正持有账号锁并写入未提交许可，改密必须等待，随后使新旧会话均失效。
    @Test
    void passwordChangeWaitingForActualLoginInvalidatesCommittedGrant() throws Exception {
        changeWaitingForLogin(false);
    }

    // 相同生产顺序下禁用等待签发事务，禁止拿锁内原生探针替代本次实际用例。
    @Test
    void disableWaitingForActualLoginInvalidatesCommittedGrant() throws Exception {
        changeWaitingForLogin(true);
    }

    private void changeWaitingForLogin(boolean disable) throws Exception {
        String name = "race.waiting";
        UUID account = registerRaceAccount(name);
        var auth =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        var normal =
                application.getBean(org.domainforge.iam.application.command.authentication.Login.class);
        var old = auth.current(normal.execute(name, PASSWORD, "127.0.0.33").token());
        String admin =
                auth.current(normal.execute("initial.admin", PASSWORD, "127.0.0.34").token()).tokenDigest();
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var login =
                loginUsing(
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.NativeSessions.class),
                        pausedGrants(inserted, release));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var signed = pool.submit(() -> login.execute(name, PASSWORD, "127.0.0.35"));
            assertTrue(inserted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var changed =
                    pool.submit(
                            () -> {
                                if (disable)
                                    return application
                                            .getBean(org.domainforge.iam.application.command.account.ManageAccount.class)
                                            .state(admin, UUID.randomUUID(), account, 1, false);
                                return application
                                        .getBean(
                                                org.domainforge.iam.application.command.authentication.ChangePassword.class)
                                        .execute(old, UUID.randomUUID(), PASSWORD, PASSWORD + " changed");
                            });
            try {
                awaitAccountWait();
            } finally {
                release.countDown();
            }
            var result = signed.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(changed.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IamException.class, () -> auth.current(result.token()));
            assertThrows(IamException.class, () -> auth.require(old.tokenDigest(), null));
        } finally {
            release.countDown();
        }
        assertEquals(2, application.getBean(AccountRepository.class).find(account).credentialVersion());
    }

    // 延迟约束在真正 COMMIT 失败；实际 Login 不返回 token、不重试且 SQL 无许可。
    @Test
    void actualLoginCommitFailureDoesNotPublishOrRegisterToken() {
        registerRaceAccount("race.commit");
        var observed =
                new PausedNativeSessions(
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.NativeSessions.class));
        observed.resume.countDown();
        var login =
                loginUsing(
                        observed,
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.SessionStore.class));
        sql.installGrantCommitFailure();
        try {
            var failure =
                    assertThrows(
                            IamException.class, () -> login.execute("race.commit", PASSWORD, "127.0.0.36"));
            assertEquals(IamFailure.UNKNOWN_RESULT, failure.failure());
            assertNotNull(observed.token);
            assertEquals(
                    0,
                    sql.tokenSessionCount(
                            application.getBean(SecretOperations.class).digest(observed.token)));
            assertThrows(
                    IamException.class,
                    () ->
                            application
                                    .getBean(org.domainforge.iam.application.authorization.CurrentAuthorization.class)
                                    .current(observed.token));
            assertEquals(0, sql.sessionCount());
        } finally {
            sql.removeGrantCommitFailure();
        }
    }

    // 已提交后确认丢失必须报未知；同一真实操作收据支持核对与重放且只产生一次副作用。
    @Test
    void unknownCommitRemainsUnknownAndCanBeReconciled() {
        var manager =
                application.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        var uncertain =
                new org.domainforge.iam.infrastructure.persistence.operation.SpringTransactions(
                        new CommitAcknowledgementLoss(manager));
        UUID operation = UUID.randomUUID(), target = UUID.randomUUID();
        String scope = "account:" + administrator;
        String fingerprint =
                application.getBean(SecretOperations.class).fingerprint("controlled-commit-loss");
        var receipts =
                application.getBean(org.domainforge.iam.application.port.operation.CommandReceipts.class);
        var expected = new CommandResult(operation, target, 1, "account.state");
        var failure =
                assertThrows(
                        IamException.class,
                        () ->
                                uncertain.execute(
                                        () -> {
                                            receipts.previous(scope, operation, fingerprint);
                                            receipts.save(scope, fingerprint, expected);
                                            return expected;
                                        }));
        assertEquals(IamFailure.UNKNOWN_RESULT, failure.failure());
        assertEquals(1, sql.operationCount(operation.toString()));
        assertEquals(
                expected,
                application
                        .getBean(Transactions.class)
                        .execute(() -> receipts.previous(scope, operation, fingerprint)));
    }

    // 驱动提交已完成再抛SQLException，原生译为DataAccessException也不能误报确定未发生。
    @Test
    void jdbcTranslatedCommitFailureRemainsUnknownAndKeepsReceipt() throws Exception {
        var source = new JdbcCommitAcknowledgementLoss(application.getBean(javax.sql.DataSource.class));
        var manager = new org.springframework.jdbc.support.JdbcTransactionManager(source);
        var receipts = source.receipts();
        var secrets = application.getBean(SecretOperations.class);
        String scope = "account:" + administrator;
        String fingerprint = secrets.fingerprint("controlled-jdbc-commit");
        var raw = new CommandResult(UUID.randomUUID(), UUID.randomUUID(), 1, "account.state");
        assertThrows(
                org.springframework.dao.RecoverableDataAccessException.class,
                () ->
                        new org.springframework.transaction.support.TransactionTemplate(manager)
                                .execute(
                                        status -> {
                                            receipts.save(scope, fingerprint, raw);
                                            return raw;
                                        }));
        assertEquals(1, sql.operationCount(raw.operationId().toString()));
        var expected = new CommandResult(UUID.randomUUID(), UUID.randomUUID(), 1, "account.state");
        var failure =
                assertThrows(
                        IamException.class,
                        () ->
                                new org.domainforge.iam.infrastructure.persistence.operation.SpringTransactions(
                                        manager)
                                        .execute(
                                                () -> {
                                                    receipts.save(scope, fingerprint, expected);
                                                    return expected;
                                                }));
        assertEquals(IamFailure.UNKNOWN_RESULT, failure.failure());
        assertEquals(1, sql.operationCount(expected.operationId().toString()));
        assertEquals(
                expected,
                application
                        .getBean(Transactions.class)
                        .execute(
                                () ->
                                        application
                                                .getBean(
                                                        org.domainforge.iam.application.port.operation.CommandReceipts.class)
                                                .previous(scope, expected.operationId(), fingerprint)));
    }

    // 十个真实业务提交同时进入普通审计时，业务连接应已归还，不能同池再借导致饥饿。
    @Test
    void ordinaryAuditReleasesBusinessConnectionsBeforeBorrowingAgain() throws Exception {
        var source =
                application
                        .getBean(javax.sql.DataSource.class)
                        .unwrap(com.zaxxer.hikari.HikariDataSource.class);
        assertEquals(10, source.getMaximumPoolSize());
        assertEquals(3000, source.getConnectionTimeout());
        var meter = source.getHikariPoolMXBean();
        var arrived = new java.util.concurrent.CountDownLatch(10);
        var release = new java.util.concurrent.CountDownLatch(1);
        var observed = new java.util.concurrent.atomic.AtomicInteger();
        var audit = application.getBean(org.domainforge.iam.application.port.audit.AuditTrail.class);
        var command =
                issueUsing(
                        application.getBean(Transactions.class),
                        event -> {
                            observed.incrementAndGet();
                            arrived.countDown();
                            try {
                                assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                            audit.record(event);
                        });
        String token =
                application
                        .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                        .execute("initial.admin", PASSWORD, "127.0.0.29")
                        .token();
        String digest = application.getBean(SecretOperations.class).digest(token);
        var futures =
                new java.util.ArrayList<
                        java.util.concurrent.Future<
                                org.domainforge.iam.application.command.registration.InvitationIssued>>();
        try (var pool = Executors.newFixedThreadPool(10)) {
            try {
                for (int index = 0; index < 10; index++) {
                    String name = "audit.parallel." + index;
                    futures.add(pool.submit(() -> command.execute(digest, UUID.randomUUID(), name, null)));
                }
                assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS));
                int activeBeforeAudit = meter.getActiveConnections();
                System.out.println(
                        "AUDIT_POOL before_borrow active="
                                + activeBeforeAudit
                                + " awaiting="
                                + meter.getThreadsAwaitingConnection());
                release.countDown();
                Thread.sleep(200);
                System.out.println(
                        "AUDIT_POOL after_borrow active="
                                + meter.getActiveConnections()
                                + " awaiting="
                                + meter.getThreadsAwaitingConnection());
                assertEquals(0, activeBeforeAudit, "普通审计前业务连接尚未释放");
                for (var future : futures) {
                    assertEquals(
                            1,
                            sql.targetAuditCount(
                                    future
                                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                                            .result()
                                            .targetId()
                                            .toString()));
                }
                assertEquals(10, observed.get());
            } finally {
                release.countDown();
                for (var future : futures) future.cancel(true);
            }
        }
        assertEquals(0, meter.getActiveConnections());
        assertEquals(0, meter.getThreadsAwaitingConnection());
    }

    // 未确认提交不能新增completed；已确认操作的重放也不能再次发送完成审计。
    @Test
    void invitationUnknownAndReplayDoNotInventCompletedAudits() {
        String token =
                application
                        .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                        .execute("initial.admin", PASSWORD, "127.0.0.30")
                        .token();
        String digest = application.getBean(SecretOperations.class).digest(token);
        var observed = new java.util.concurrent.atomic.AtomicInteger();
        var audit = application.getBean(org.domainforge.iam.application.port.audit.AuditTrail.class);
        org.domainforge.iam.application.port.audit.AuditTrail recording =
                event -> {
                    observed.incrementAndGet();
                    audit.record(event);
                };
        var uncertain =
                new org.domainforge.iam.infrastructure.persistence.operation.SpringTransactions(
                        new CommitAcknowledgementLoss(
                                application.getBean(
                                        org.springframework.transaction.PlatformTransactionManager.class)));
        UUID unknownOperation = UUID.randomUUID();
        var failure =
                assertThrows(
                        IamException.class,
                        () ->
                                issueUsing(uncertain, recording)
                                        .execute(digest, unknownOperation, "audit.unknown", null));
        assertEquals(IamFailure.UNKNOWN_RESULT, failure.failure());
        assertEquals(1, sql.operationCount(unknownOperation.toString()));
        assertEquals(0, observed.get());
        var normal = issueUsing(application.getBean(Transactions.class), recording);
        UUID operation = UUID.randomUUID();
        var created = normal.execute(digest, operation, "audit.replay", null);
        assertEquals(1, observed.get());
        assertEquals(
                created.result(), normal.execute(digest, operation, "audit.replay", null).result());
        assertEquals(1, observed.get());
        assertEquals(1, sql.targetAuditCount(created.result().targetId().toString()));
        assertThrows(
                IamException.class,
                () -> issueUsing(uncertain, recording).execute(digest, operation, "audit.replay", null));
        assertEquals(1, observed.get());
        assertEquals(1, sql.targetAuditCount(created.result().targetId().toString()));
    }

    // 已完成注册的重放只返回原结果，不依赖数据库去重掩盖再次发送的完成事件。
    @Test
    void registrationReplayDoesNotEmitCompletedAuditTwice() {
        String secret = invite("audit.registration");
        var observed = new java.util.concurrent.atomic.AtomicInteger();
        var audit = application.getBean(org.domainforge.iam.application.port.audit.AuditTrail.class);
        var command =
                new AcceptInvitation(
                        application.getBean(Transactions.class),
                        application.getBean(org.domainforge.iam.application.port.operation.DatabaseTime.class),
                        application.getBean(AccountRepository.class),
                        application.getBean(InvitationRepository.class),
                        application.getBean(
                                org.domainforge.iam.application.port.operation.CommandReceipts.class),
                        application.getBean(
                                org.domainforge.iam.application.port.authentication.Passwords.class),
                        application.getBean(SecretOperations.class),
                        event -> {
                            observed.incrementAndGet();
                            audit.record(event);
                        });
        UUID operation = UUID.randomUUID();
        var result = command.execute(operation, secret, "audit.registration", PASSWORD);
        assertEquals(result, command.execute(operation, secret, "audit.registration", PASSWORD));
        assertEquals(1, observed.get());
        assertEquals(1, sql.targetAuditCount(result.targetId().toString()));
    }

    // 邀请、补录和本人改密不改变最后管理者，不能因保全锁阻塞无关账号命令。
    @Test
    void unrelatedSecurityCommandsDoNotWaitForAdministratorPreservation() throws Exception {
        var authorization =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        var current =
                authorization.current(
                        application
                                .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                                .execute("initial.admin", PASSWORD, "127.0.0.21")
                                .token());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var preservation =
                    pool.submit(
                            () ->
                                    application
                                            .getBean(Transactions.class)
                                            .execute(
                                                    () -> {
                                                        application
                                                                .getBean(
                                                                        org.domainforge.iam.application.port.operation
                                                                                .AdministrationGuard.class)
                                                                .lock();
                                                        locked.countDown();
                                                        try {
                                                            assertTrue(release.await(15, java.util.concurrent.TimeUnit.SECONDS));
                                                        } catch (InterruptedException failure) {
                                                            Thread.currentThread().interrupt();
                                                            throw new AssertionError(failure);
                                                        }
                                                        return true;
                                                    }));
            try {
                assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var independent =
                        pool.submit(
                                () -> {
                                    var invitation =
                                            application
                                                    .getBean(
                                                            org.domainforge.iam.application.command.registration.IssueInvitation
                                                                    .class)
                                                    .execute(
                                                            current.tokenDigest(), UUID.randomUUID(), "independent.user", null);
                                    assertNotNull(invitation.secret());
                                    var entry =
                                            new org.domainforge.iam.application.port.audit.ManualAudit(
                                                    UUID.randomUUID(),
                                                    administrator,
                                                    "invitation.accept",
                                                    null,
                                                    "ticket:IAM-B1",
                                                    java.util.Set.of("occurredAt", "originalActor", "originalOutcome"));
                                    assertNotNull(
                                            application
                                                    .getBean(
                                                            org.domainforge.iam.application.command.audit.BackfillAudit.class)
                                                    .execute(current.tokenDigest(), UUID.randomUUID(), entry));
                                    assertNotNull(
                                            application
                                                    .getBean(
                                                            org.domainforge.iam.application.command.authentication.ChangePassword
                                                                    .class)
                                                    .execute(current, UUID.randomUUID(), PASSWORD, PASSWORD + " revised"));
                                    return true;
                                });
                assertTrue(independent.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(IamException.class, () -> authorization.require(current.tokenDigest(), null));
                assertEquals(1, sql.manualAuditCount());
            } finally {
                release.countDown();
            }
            assertTrue(preservation.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    // 事务内已完成授权的在途邀请可以完成；撤权提交后的新命令及旧收据重放必须拒绝。
    @Test
    void revocationDoesNotRetroactivelyCancelAuthorizedInvitation() throws Exception {
        var login =
                application.getBean(org.domainforge.iam.application.command.authentication.Login.class);
        var authorization =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        var manager =
                application.getBean(org.domainforge.iam.application.command.account.ManageAccount.class);
        var accounts = application.getBean(AccountRepository.class);
        String actor =
                authorization
                        .current(login.execute("initial.admin", PASSWORD, "127.0.0.22").token())
                        .tokenDigest();
        UUID other =
                application
                        .getBean(AcceptInvitation.class)
                        .execute(UUID.randomUUID(), invite("concurrent.admin"), "concurrent.admin", PASSWORD)
                        .targetId();
        manager.permissions(
                actor,
                UUID.randomUUID(),
                other,
                1,
                org.domainforge.iam.domain.model.Account.MANAGEMENT_PERMISSIONS);
        String second =
                authorization
                        .current(login.execute("concurrent.admin", PASSWORD, "127.0.0.23").token())
                        .tokenDigest();
        var authorized = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        InvitationRepository actual = application.getBean(InvitationRepository.class);
        InvitationRepository paused =
                new InvitationRepository() {
                    @Override
                    public RegistrationInvitation lockByDigest(String digest) {
                        return actual.lockByDigest(digest);
                    }

                    @Override
                    public RegistrationInvitation lock(UUID id) {
                        return actual.lock(id);
                    }

                    @Override
                    public void finish(RegistrationInvitation invitation) {
                        actual.finish(invitation);
                    }

                    @Override
                    public void insert(RegistrationInvitation invitation) {
                        authorized.countDown();
                        try {
                            assertTrue(finish.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        actual.insert(invitation);
                    }
                };
        var issue =
                new org.domainforge.iam.application.command.registration.IssueInvitation(
                        application.getBean(Transactions.class),
                        application.getBean(org.domainforge.iam.application.port.operation.DatabaseTime.class),
                        authorization,
                        accounts,
                        paused,
                        application.getBean(
                                org.domainforge.iam.application.port.operation.CommandReceipts.class),
                        application.getBean(SecretOperations.class),
                        application.getBean(org.domainforge.iam.application.port.audit.AuditTrail.class));
        UUID operation = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var inFlight =
                    pool.submit(() -> issue.execute(actor, operation, "authorized.inflight", null));
            try {
                assertTrue(authorized.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var revoke =
                        pool.submit(
                                () ->
                                        manager.permissions(
                                                second,
                                                UUID.randomUUID(),
                                                administrator,
                                                accounts.find(administrator).version(),
                                                java.util.Set.of()));
                assertNotNull(revoke.get(3, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                finish.countDown();
            }
            assertNotNull(inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS).secret());
        }
        assertEquals(1, sql.operationCount(operation.toString()));
        var failure =
                assertThrows(
                        IamException.class, () -> issue.execute(actor, operation, "authorized.inflight", null));
        assertEquals(IamFailure.FORBIDDEN, failure.failure());
    }

    // 普通原生会话只表达Redis事实；本地完整源仍拒绝SQL故障和已撤销许可。
    @Test
    void localSourcePreservesFailureAndRevocation() {
        var nativeLogic = application.getBean(cn.dev33.satoken.stp.StpLogic.class);
        var source =
                new org.domainforge.iam.interfaces.security.IamCurrentSubjectSource(
                        application.getBean(
                                org.domainforge.iam.application.authorization.CurrentAuthorization.class),
                        "test-project");
        try {
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext();
            assertEquals(null, nativeLogic.getLoginIdDefaultNull());
            assertFalse(nativeLogic.isLogin());
            assertEquals(
                    org.domainforge.access.api.AccessFailure.UNAUTHENTICATED,
                    assertThrows(org.domainforge.access.api.AccessException.class, () -> source.resolve(null))
                            .failure());
            application
                    .getBean(AcceptInvitation.class)
                    .execute(UUID.randomUUID(), invite("native.contract"), "native.contract", PASSWORD);
            var login =
                    application
                            .getBean(org.domainforge.iam.application.command.authentication.Login.class)
                            .execute("native.contract", PASSWORD, "127.0.0.12");
            nativeLogic.setTokenValue(login.token());
            assertTrue(nativeLogic.isLogin());
            assertNotNull(nativeLogic.getLoginIdDefaultNull());
            assertEquals(
                    application.getBean(AccountRepository.class).findByLoginName("native.contract").id(),
                    source.resolve(login.token()).accountId());
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.clearContext();
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext();
            nativeLogic.setTokenValue(login.token());
            sql.hideSessionTable();
            try {
                assertTrue(nativeLogic.isLogin());
                assertEquals(
                        org.domainforge.access.api.AccessFailure.UNAVAILABLE,
                        assertThrows(
                                org.domainforge.access.api.AccessException.class,
                                () -> source.resolve(login.token()))
                                .failure());
            } finally {
                sql.restoreSessionTable();
            }
            assertEquals(
                    application.getBean(AccountRepository.class).findByLoginName("native.contract").id(),
                    source.resolve(login.token()).accountId());
            sql.deleteSession(application.getBean(SecretOperations.class).digest(login.token()));
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.clearContext();
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext();
            nativeLogic.setTokenValue(login.token());
            assertNotNull(nativeLogic.getLoginIdDefaultNull());
            assertTrue(nativeLogic.isLogin());
            assertEquals(
                    org.domainforge.access.api.AccessFailure.UNAUTHENTICATED,
                    assertThrows(
                            org.domainforge.access.api.AccessException.class,
                            () -> source.resolve(login.token()))
                            .failure());
        } finally {
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.clearContext();
        }
    }

    // 重发与接受在两个真实事务并发竞争，旧秘密失效或原注册成功只能择一，最终至多一账号。
    @Test
    void reissueAndAcceptanceCannotBothConsumeOriginal() throws Exception {
        var login =
                application.getBean(org.domainforge.iam.application.command.authentication.Login.class);
        var auth =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        String actor =
                auth.current(login.execute("initial.admin", PASSWORD, "127.0.0.13").token()).tokenDigest();
        var issue =
                application.getBean(
                        org.domainforge.iam.application.command.registration.IssueInvitation.class);
        var accept = application.getBean(AcceptInvitation.class);
        var original = issue.execute(actor, UUID.randomUUID(), "reissue.race", null);
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var replacement =
                    pool.submit(
                            () -> {
                                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                                try {
                                    return issue.execute(
                                            actor, UUID.randomUUID(), "reissue.race", original.result().targetId());
                                } catch (IamException failure) {
                                    assertEquals(IamFailure.CONFLICT, failure.failure());
                                    return null;
                                }
                            });
            var registration =
                    pool.submit(
                            () -> {
                                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                                return attempt(accept, UUID.randomUUID(), original.secret(), "reissue.race");
                            });
            var renewed = replacement.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var registered = registration.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((renewed == null) != (registered == null));
            if (renewed != null) {
                assertNotNull(
                        accept.execute(UUID.randomUUID(), renewed.secret(), "reissue.race", PASSWORD));
            }
            assertThrows(
                    IamException.class,
                    () -> accept.execute(UUID.randomUUID(), original.secret(), "reissue.race", PASSWORD));
        }
        assertEquals(1, sql.reissuedAccountCount());
    }

    // 撤权后不能用旧operationId读取成功收据，另一个有权主体的旧版本修改也必须冲突。
    @Test
    void revokedActorCannotReplayOldReceiptAndStaleVersionRejects() {
        var login =
                application.getBean(org.domainforge.iam.application.command.authentication.Login.class);
        var auth =
                application.getBean(
                        org.domainforge.iam.application.authorization.CurrentAuthorization.class);
        var manage =
                application.getBean(org.domainforge.iam.application.command.account.ManageAccount.class);
        var accounts = application.getBean(AccountRepository.class);
        String first =
                auth.current(login.execute("initial.admin", PASSWORD, "127.0.0.14").token()).tokenDigest();
        UUID secondId =
                application
                        .getBean(AcceptInvitation.class)
                        .execute(UUID.randomUUID(), invite("second.admin"), "second.admin", PASSWORD)
                        .targetId();
        manage.permissions(
                first,
                UUID.randomUUID(),
                secondId,
                1,
                org.domainforge.iam.domain.model.Account.MANAGEMENT_PERMISSIONS);
        String second =
                auth.current(login.execute("second.admin", PASSWORD, "127.0.0.15").token()).tokenDigest();
        var accept = application.getBean(AcceptInvitation.class);
        UUID target =
                accept
                        .execute(UUID.randomUUID(), invite("receipt.target"), "receipt.target", PASSWORD)
                        .targetId();
        UUID operation = UUID.randomUUID();
        var saved =
                manage.permissions(first, operation, target, 1, java.util.Set.of("iam:account:read"));
        assertEquals(
                saved,
                manage.permissions(first, operation, target, 1, java.util.Set.of("iam:account:read")));
        var stale =
                assertThrows(
                        IamException.class,
                        () -> manage.permissions(second, UUID.randomUUID(), target, 1, java.util.Set.of()));
        assertEquals(IamFailure.CONFLICT, stale.failure());
        var revoked =
                manage.permissions(
                        second,
                        UUID.randomUUID(),
                        administrator,
                        accounts.find(administrator).version(),
                        java.util.Set.of());
        try {
            var failure =
                    assertThrows(
                            IamException.class,
                            () ->
                                    manage.permissions(
                                            first, operation, target, 1, java.util.Set.of("iam:account:read")));
            assertEquals(IamFailure.FORBIDDEN, failure.failure());
            assertEquals(saved.version(), accounts.find(target).version());
        } finally {
            manage.permissions(
                    second,
                    UUID.randomUUID(),
                    administrator,
                    revoked.version(),
                    org.domainforge.iam.domain.model.Account.MANAGEMENT_PERMISSIONS);
        }
    }

    // 部署不一致的实例既不能用更宽策略放行，也不能覆盖数据库当前策略。
    @Test
    void mismatchedPolicyCannotAdmitOrOverwrite() {
        var original = application.getBean(LoginProtection.class);
        String digest = original.policyStatus().currentDigest();
        var changed = protection(new LoginProtectionSettings(4, 600, 30, 60, 2, 8, 1000));
        assertEquals(
                IamFailure.UNAVAILABLE,
                assertThrows(IamException.class, () -> changed.reserve("policy.user", "10.0.0.1"))
                        .failure());
        assertEquals(digest, sql.protectionDigest());
        assertNotNull(original.reserve("policy.user", "10.0.0.1"));
        assertEquals(
                IamFailure.CONFLICT,
                assertThrows(IamException.class, () -> changed.changePolicy("0".repeat(64))).failure());
        assertEquals(digest, sql.protectionDigest());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MaintainLoginPolicy(changed).apply(digest, false));
    }

    // 窗口先从600延长到3600，再改回600；数据库仍须保留旧长窗口。
    @Test
    void policyTransitionHonorsBothWindowsAndRejectsReplay() {
        var original = application.getBean(LoginProtection.class);
        var longer = protection(new LoginProtectionSettings(4, 3600, 20, 600, 1, 2, 500));
        String before = sql.protectionDigest();
        var changed = new MaintainLoginPolicy(longer).apply(before, true);
        assertEquals(changed.configuredDigest(), sql.protectionDigest());
        assertTrue(sql.protectionRemainingSeconds() >= 3590);
        assertEquals(
                IamFailure.UNAVAILABLE,
                assertThrows(IamException.class, () -> longer.reserve("policy.user", "10.0.0.1"))
                        .failure());
        assertEquals(
                IamFailure.UNAVAILABLE,
                assertThrows(IamException.class, () -> original.reserve("policy.user", "10.0.0.1"))
                        .failure());
        var deadline = sql.protectionDeadline();
        assertThrows(IamException.class, () -> longer.changePolicy(before));
        assertThrows(IamException.class, () -> longer.changePolicy(changed.currentDigest()));
        assertEquals(deadline, sql.protectionDeadline());
        original.changePolicy(changed.currentDigest());
        assertEquals(before, sql.protectionDigest());
        assertTrue(sql.protectionRemainingSeconds() >= 3590);
    }

    // 已有更晚截止不因显式切换或Redis状态丢失变短，错误配置也不能触发代次覆盖。
    @Test
    void restartAndPolicyChangeNeverShortenExistingRecovery() {
        var original = application.getBean(LoginProtection.class);
        var changed = protection(new LoginProtectionSettings(4, 600, 30, 60, 2, 8, 1000));
        sql.extendProtectionWindow(7200);
        var deadline = sql.protectionDeadline();
        var status = changed.changePolicy(sql.protectionDigest());
        assertEquals(deadline, status.blockedUntil());
        application.getBean(StringRedisTemplate.class).delete("iam:protection:generation");
        assertThrows(IamException.class, () -> original.reserve("policy.user", "10.0.0.1"));
        assertEquals(deadline, sql.protectionDeadline());
        assertThrows(IamException.class, () -> changed.reserve("policy.user", "10.0.0.1"));
        assertEquals(deadline, sql.protectionDeadline());
    }

    // 新项目可直接登记收紧策略；共享账号额度跨适配实例计算，成功只移除自身预占。
    @Test
    void tightenedPolicyRegistersAtInitializationAndSharesReservations() {
        sql.reset();
        var settings = new LoginProtectionSettings(1, 600, 2, 60, 1, 1, 100);
        var one = protection(settings);
        var two = protection(settings);
        application
                .getBean(Transactions.class)
                .execute(
                        () -> {
                            one.initialize();
                            return null;
                        });
        var attempt = one.reserve("limited.user", "10.0.0.1");
        var account = assertThrows(IamException.class, () -> two.reserve("limited.user", "10.0.0.2"));
        assertEquals(IamFailure.RATE_LIMITED, account.failure());
        assertTrue(account.retryAfterSeconds() >= 590 && account.retryAfterSeconds() <= 600);
        one.succeeded(attempt);
        assertNotNull(two.reserve("limited.user", "10.0.0.1"));
        var address = assertThrows(IamException.class, () -> two.reserve("another.user", "10.0.0.1"));
        assertEquals(IamFailure.RATE_LIMITED, address.failure());
        assertTrue(address.retryAfterSeconds() >= 50 && address.retryAfterSeconds() <= 60);
        assertThrows(IamException.class, one::initialize);
        assertEquals(settings.digest(), sql.protectionDigest());
    }

    // 真实子进程传入与维护模式相反的部署属性，维护CLI仍须完全非Web且正常读取策略。
    @Test
    void maintenanceCliCannotBeOverriddenToWebMode() throws Exception {
        var log = java.nio.file.Files.createTempFile("iam-maintenance-mode-", ".log");
        var process =
                new ProcessBuilder(
                        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dspring.main.web-application-type=servlet",
                        "-Dserver.port=invalid-maintenance-port",
                        "-cp",
                        System.getProperty("java.class.path"),
                        IamApplication.class.getName(),
                        "login-policy",
                        "status")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS), "CLI结束超时: " + log);
            assertEquals(0, process.exitValue(), "非Web维护被部署属性覆盖: " + log);
            assertTrue(java.nio.file.Files.readString(log).contains("login_policy currentDigest="));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
