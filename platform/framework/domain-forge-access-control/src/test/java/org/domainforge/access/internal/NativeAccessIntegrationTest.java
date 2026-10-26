package org.domainforge.access.internal;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.shared.CurrentSubject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Boot 自动配置和 MVC 执行对照；角色仅为原生机制夹具，不代表 IAM 已实现 RBAC。
 */
class NativeAccessIntegrationTest {
    private static final long ACCOUNT = 1_000_001L;
    private static final AtomicInteger READS = new AtomicInteger();
    private static volatile AccessFailure failure;

    // 明确匿名、仅登录与未声明入口分别验证，不让后续成功登录掩盖入口错误。
    private static void assertAnonymousAndDefaultDeny(MockMvc mvc) throws Exception {
        assertEquals(200, call(mvc, "/anonymous", null));
        assertEquals(200, call(mvc, "/class-anonymous", null));
        assertEquals(401, call(mvc, "/class-permission", null));
        assertEquals(401, call(mvc, "/login", null));
        assertEquals(403, call(mvc, "/undeclared", null));
    }

    // 方法、类及原生 OR/orRole 均保留真实允许和拒绝断言。
    private static void assertNativeProtectionMatrix(MockMvc mvc, String token) throws Exception {
        assertEquals(200, call(mvc, "/role", token));
        assertEquals(403, call(mvc, "/denied-role", token));
        assertEquals(200, call(mvc, "/or", token));
        assertEquals(200, call(mvc, "/class-permission", token));
        assertEquals(403, call(mvc, "/class-permission-denied", token));
        assertEquals(200, call(mvc, "/permission-or", token));
        assertEquals(403, call(mvc, "/permission-or-denied", token));
        assertEquals(200, call(mvc, "/permission-or-role", token));
        assertEquals(403, call(mvc, "/permission-or-role-denied", token));
    }

    // 同一已签发会话的依赖故障、许可失效和原生退出保持不同失败语义。
    private static void assertFailuresAndRevocation(MockMvc mvc, StpLogic logic, String token) throws Exception {
        failure = AccessFailure.UNAVAILABLE;
        assertEquals(503, call(mvc, "/or", token));
        failure = AccessFailure.UNAUTHENTICATED;
        assertEquals(401, call(mvc, "/login", token));
        failure = null;
        logic.logoutByTokenValue(token);
        assertEquals(401, call(mvc, "/login", token));
    }

    // 包装原生存储：第 n 次 get 按 fault 决定抛出故障还是返回空（会话失效），其余调用透传。
    private static SaTokenDao faultyStore(
            SaTokenDao original, AtomicInteger reads, IntPredicate fault, boolean outage) {
        return (SaTokenDao) Proxy.newProxyInstance(
                NativeAccessIntegrationTest.class.getClassLoader(),
                new Class<?>[]{SaTokenDao.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("get") && fault.test(reads.incrementAndGet())) {
                        if (outage) throw new IllegalStateException("synthetic-native-store-outage");
                        return null;
                    }
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    // 故障分别保留原生 401 分类与公共 503，异常中不能出现实际令牌。
    private static void assertSanitizedFailure(MvcResult result, String token, boolean outage) {
        assertEquals(outage ? 503 : 401, result.getResponse().getStatus());
        if (outage) {
            var error = assertInstanceOf(AccessException.class, result.getResolvedException());
            assertEquals(AccessFailure.UNAVAILABLE, error.failure());
        } else {
            var error = assertInstanceOf(NotLoginException.class, result.getResolvedException());
            assertEquals("login", error.getLoginType());
            assertEquals(NotLoginException.INVALID_TOKEN, error.getType());
            assertFalse(error.getMessage().contains(token));
        }
    }

    private static ConfigurableApplicationContext start(Class<?>... extra) {
        var sources = new ArrayList<Class<?>>();
        sources.add(Application.class);
        sources.addAll(List.of(extra));
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new)).run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--domainforge.access.session-store=memory",
                "--spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,"
                        + "org.redisson.spring.starter.RedissonAutoConfigurationV4");
    }

    private static MockMvc mvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(new SaTokenContextFilterForJakartaServlet())
                .build();
    }

    private static int call(MockMvc mvc, String path, String token) throws Exception {
        return perform(mvc, path, token).getResponse().getStatus();
    }

    private static MvcResult perform(MockMvc mvc, String path, String token) throws Exception {
        var request = MockMvcRequestBuilders.get(path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    private static void assertStartupFails(Class<?> invalid) {
        assertThrows(RuntimeException.class, () -> {
            try (var ignored = start(invalid)) {
                fail("非法声明不应启动成功：" + invalid.getSimpleName());
            }
        });
    }

    // 两次独立上下文证明装配可重建；原生布尔、注解、默认拒绝及请求快照都走真实 MVC。
    @Test
    void autoConfigurationUsesNativeAnnotationsAndRebuilds() throws Exception {
        for (int iteration = 0; iteration < 2; iteration++) {
            failure = null;
            try (var context = start()) {
                var mvc = mvc(context);
                var logic = context.getBean(StpLogic.class);
                assertSame(logic, StpUtil.getStpLogic());
                assertAnonymousAndDefaultDeny(mvc);
                String token = logic.createLoginSession(ACCOUNT);
                READS.set(0);
                assertEquals(200, call(mvc, "/permission", token));
                assertEquals(1, READS.get());
                assertNativeProtectionMatrix(mvc, token);
                assertFailuresAndRevocation(mvc, logic, token);
            }
        }
    }

    // 多值 AND 必须同时满足全部原生权限／角色，不能被误实现为任一项即可。
    @Test
    void nativeAndRequiresEveryPermissionAndRole() throws Exception {
        failure = null;
        try (var context = start()) {
            var mvc = mvc(context);
            String token = context.getBean(StpLogic.class).createLoginSession(ACCOUNT);
            assertEquals(200, call(mvc, "/permission-and", token));
            assertEquals(403, call(mvc, "/permission-and-denied", token));
            assertEquals(200, call(mvc, "/role-and", token));
            assertEquals(403, call(mvc, "/role-and-denied", token));
        }
    }

    // 主体验证之后的原生存储读取故障，在入口校验、注解执行和权限桥接阶段都保持 503。
    @Test
    void laterNativeStoreFailuresRemainUnavailable() throws Exception {
        failure = null;
        try (var context = start()) {
            var mvc = mvc(context);
            String token = context.getBean(StpLogic.class).createLoginSession(ACCOUNT);
            var original = SaManager.getSaTokenDao();
            try {
                for (int failAt : new int[]{2, 3, 4}) {
                    var reads = new AtomicInteger();
                    SaManager.setSaTokenDao(faultyStore(original, reads, read -> read == failAt, true));
                    assertSanitizedFailure(perform(mvc, "/permission", token), token, true);
                    assertTrue(reads.get() >= failAt);
                }
            } finally {
                SaManager.setSaTokenDao(original);
            }
            assertEquals(200, call(mvc, "/permission", token));
        }
    }

    // 主体验证之后原生会话失效仍为 401，原生异常分类保留且不能拼接实际令牌。
    @Test
    void laterNativeInvalidationNeverExposesTokenInException() throws Exception {
        failure = null;
        try (var context = start()) {
            var mvc = mvc(context);
            String token = context.getBean(StpLogic.class).createLoginSession(ACCOUNT);
            var original = SaManager.getSaTokenDao();
            try {
                for (int invalidAt : new int[]{2, 3}) {
                    var reads = new AtomicInteger();
                    SaManager.setSaTokenDao(faultyStore(original, reads, read -> read == invalidAt, false));
                    assertSanitizedFailure(perform(mvc, "/permission", token), token, false);
                    assertTrue(reads.get() >= invalidAt);
                }
            } finally {
                SaManager.setSaTokenDao(original);
            }
        }
    }

    // 控制器程序式调用原生提供者时已离开拦截器，权限桥接自身仍负责安全的原生读取边界。
    @Test
    void programmaticProviderReadSanitizesInvalidationAndOutage() throws Exception {
        failure = null;
        try (var context = start()) {
            var mvc = mvc(context);
            String token = context.getBean(StpLogic.class).createLoginSession(ACCOUNT);
            var original = SaManager.getSaTokenDao();
            try {
                for (boolean outage : new boolean[]{false, true}) {
                    var reads = new AtomicInteger();
                    SaManager.setSaTokenDao(faultyStore(original, reads, read -> read == 4, outage));
                    assertSanitizedFailure(perform(mvc, "/programmatic-provider", token), token, outage);
                    assertEquals(4, reads.get());
                }
            } finally {
                SaManager.setSaTokenDao(original);
            }
        }
    }

    // 无法被原生执行的子类策略、空声明、匿名混合及非支持分支都必须拒绝启动。
    @Test
    void unsupportedDeclarationsFailAtStartup() {
        for (var invalid : List.of(
                InheritedProtected.class,
                EmptyPermission.class,
                AnonymousMixed.class,
                UnsupportedSafe.class,
                EmptyOr.class,
                NestedOtherType.class,
                PermissionWithUnsupportedSafe.class))
            assertStartupFails(invalid);
    }

    // 未支持分支的处理器被移除或替换时不影响正常接入，其声明仍在启动时拒绝。
    @Test
    void unusedUnsupportedHandlersDoNotRestrictSupportedAccess() throws Exception {
        var handlers = SaAnnotationStrategy.instance.annotationHandlerMap;
        var unsupported =
                List.of(SaCheckSafe.class, SaCheckHttpBasic.class, SaCheckHttpDigest.class, SaCheckDisable.class);
        var previous = new HashMap<>(handlers);
        try {
            for (boolean replace : new boolean[]{false, true}) {
                for (var kind : unsupported) {
                    if (replace) handlers.put(kind, previous.get(SaCheckLogin.class));
                    else handlers.remove(kind);
                }
                failure = null;
                try (var context = start()) {
                    var mvc = mvc(context);
                    var logic = context.getBean(StpLogic.class);
                    assertEquals(200, call(mvc, "/permission", logic.createLoginSession(ACCOUNT)));
                    assertEquals(401, call(mvc, "/permission", null));
                }
                for (var invalid : List.of(
                        UnsupportedSafe.class,
                        UnsupportedBasic.class,
                        UnsupportedDigest.class,
                        UnsupportedDisable.class))
                    assertStartupFails(invalid);
            }
        } finally {
            handlers.clear();
            handlers.putAll(previous);
        }
    }

    /**
     * 只启用组件自动配置与测试端点；会话使用原生内存存储，不连接 Redis。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
            Endpoints.class,
            ClassPermissionEndpoint.class,
            ClassDeniedPermissionEndpoint.class,
            ClassAnonymousEndpoint.class
    })
    static class Application {
        @Bean
        CurrentSubjectSource source(StpLogic logic) {
            return token -> {
                READS.incrementAndGet();
                if (failure != null) throw new AccessException(failure);
                Object id = logic.getLoginIdByToken(token);
                if (id == null) throw new AccessException(AccessFailure.UNAUTHENTICATED);
                return new CurrentSubject(
                        Long.parseLong(id.toString()),
                        Set.of("account:read", "account:update"),
                        Set.of("manager", "auditor"));
            };
        }
    }

    /**
     * 固定原生机制端点覆盖，不包含真实组织或角色业务。
     */
    @RestController
    static class Endpoints {
        private final CurrentAccess current;

        Endpoints(CurrentAccess current) {
            this.current = current;
        }

        @SaIgnore
        @GetMapping("/anonymous")
        String anonymous() {
            assertFalse(StpUtil.isLogin());
            assertFalse(StpUtil.hasPermission("account:read"));
            assertFalse(StpUtil.hasRole("manager"));
            return "anonymous";
        }

        @SaCheckLogin
        @GetMapping("/login")
        String login() {
            return "login";
        }

        @SaCheckPermission("account:read")
        @GetMapping("/permission")
        String permission() {
            current.current();
            StpUtil.getPermissionList();
            return "permission";
        }

        @SaCheckRole("manager")
        @GetMapping("/role")
        String role() {
            return "role";
        }

        @SaCheckRole("missing")
        @GetMapping("/denied-role")
        String deniedRole() {
            return "role";
        }

        @SaCheckOr(permission = @SaCheckPermission("missing"), role = @SaCheckRole("manager"))
        @GetMapping("/or")
        String or() {
            return "or";
        }

        @SaCheckPermission(
                value = {"missing", "account:read"},
                mode = SaMode.OR)
        @GetMapping("/permission-or")
        String permissionOr() {
            return "permission-or";
        }

        @SaCheckPermission(
                value = {"missing", "other"},
                mode = SaMode.OR)
        @GetMapping("/permission-or-denied")
        String permissionOrDenied() {
            return "permission-or-denied";
        }

        @SaCheckPermission(value = "missing", orRole = "manager")
        @GetMapping("/permission-or-role")
        String permissionOrRole() {
            return "permission-or-role";
        }

        @SaCheckPermission(value = "missing", orRole = "absent")
        @GetMapping("/permission-or-role-denied")
        String permissionOrRoleDenied() {
            return "permission-or-role-denied";
        }

        @SaCheckPermission(
                value = {"account:read", "account:update"},
                mode = SaMode.AND)
        @GetMapping("/permission-and")
        String permissionAnd() {
            return "permission-and";
        }

        @SaCheckPermission(
                value = {"account:read", "missing"},
                mode = SaMode.AND)
        @GetMapping("/permission-and-denied")
        String permissionAndDenied() {
            return "permission-and-denied";
        }

        @SaCheckRole(
                value = {"manager", "auditor"},
                mode = SaMode.AND)
        @GetMapping("/role-and")
        String roleAnd() {
            return "role-and";
        }

        @SaCheckRole(
                value = {"manager", "missing"},
                mode = SaMode.AND)
        @GetMapping("/role-and-denied")
        String roleAndDenied() {
            return "role-and-denied";
        }

        @SaCheckLogin
        @GetMapping("/programmatic-provider")
        String programmaticProvider() {
            SaManager.getStpInterface().getPermissionList(String.valueOf(ACCOUNT), "login");
            return "provider";
        }

        @GetMapping("/undeclared")
        String undeclared() {
            throw new AssertionError("未声明入口不得执行");
        }
    }

    /**
     * 只有类声明的权限仍由原生执行，方法无需重复注解。
     */
    @RestController
    @SaCheckPermission("account:read")
    static class ClassPermissionEndpoint {
        @GetMapping("/class-permission")
        String current() {
            return "class-permission";
        }
    }

    /**
     * 纯类权限不足不能因方法没有重复声明而允许。
     */
    @RestController
    @SaCheckPermission("missing")
    static class ClassDeniedPermissionEndpoint {
        @GetMapping("/class-permission-denied")
        String current() {
            return "class-permission-denied";
        }
    }

    /**
     * 纯类匿名声明覆盖本类普通方法，同时保留原生匿名布尔语义。
     */
    @RestController
    @SaIgnore
    static class ClassAnonymousEndpoint {
        @GetMapping("/class-anonymous")
        String current() {
            assertFalse(StpUtil.isLogin());
            assertFalse(StpUtil.hasPermission("account:read"));
            return "class-anonymous";
        }
    }

    /**
     * 继承方法本身没有策略，子类策略不能被原生遗漏执行。
     */
    static class ParentEndpoint {
        @GetMapping("/inherited")
        public String inherited() {
            return "unsafe";
        }
    }

    /**
     * 原生使用方法声明类，拒绝无法覆盖的子类独有策略。
     */
    @RestController
    @SaCheckPermission("missing")
    static class InheritedProtected extends ParentEndpoint {
    }

    /**
     * 空权限不是登录声明。
     */
    @RestController
    static class EmptyPermission {
        @SaCheckPermission({})
        @GetMapping("/empty")
        String value() {
            return "x";
        }
    }

    /**
     * 匿名和保护不能依赖隐式覆盖顺序。
     */
    @RestController
    @SaIgnore
    static class AnonymousMixed {
        @SaCheckLogin
        @GetMapping("/mixed")
        String value() {
            return "x";
        }
    }

    /**
     * 首批不支持二级安全认证分支。
     */
    @RestController
    static class UnsupportedSafe {
        @SaCheckSafe
        @GetMapping("/safe")
        String value() {
            return "x";
        }
    }

    /**
     * 未支持的Basic声明也不依赖宿主处理器是否登记。
     */
    @RestController
    static class UnsupportedBasic {
        @SaCheckHttpBasic
        @GetMapping("/basic")
        String value() {
            return "x";
        }
    }

    /**
     * 未支持的Digest声明也不依赖宿主处理器是否登记。
     */
    @RestController
    static class UnsupportedDigest {
        @SaCheckHttpDigest
        @GetMapping("/digest")
        String value() {
            return "x";
        }
    }

    /**
     * 未支持的服务封禁声明不能退化成普通登录声明。
     */
    @RestController
    static class UnsupportedDisable {
        @SaCheckDisable("unsupported")
        @GetMapping("/disable")
        String value() {
            return "x";
        }
    }

    /**
     * 受支持权限与不支持声明混合也不能只执行前者。
     */
    @RestController
    static class PermissionWithUnsupportedSafe {
        @SaCheckPermission("iam:account:read")
        @SaCheckSafe
        @GetMapping("/permission-safe")
        String value() {
            return "x";
        }
    }

    /**
     * 空OR不能构成任何真实访问策略。
     */
    @RestController
    static class EmptyOr {
        @SaCheckOr
        @GetMapping("/empty-or")
        String value() {
            return "x";
        }
    }

    /**
     * 嵌套类型约束与直接声明完全一致。
     */
    @RestController
    static class NestedOtherType {
        @SaCheckOr(login = @SaCheckLogin(type = "other"))
        @GetMapping("/other")
        String value() {
            return "x";
        }
    }
}
