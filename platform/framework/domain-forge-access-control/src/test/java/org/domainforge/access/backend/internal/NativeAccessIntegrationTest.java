package org.domainforge.access.backend.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.dev33.satoken.annotation.SaCheckDisable;
import cn.dev33.satoken.annotation.SaCheckHttpBasic;
import cn.dev33.satoken.annotation.SaCheckHttpDigest;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaCheckSafe;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.access.backend.configuration.LocalAccessConfiguration;
import org.domainforge.shared.CurrentSubject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/** 真实Boot原生装配和MVC执行对照；角色仅为原生机制夹具，不代表IAM已实现RBAC。 */
class NativeAccessIntegrationTest {
  private static final UUID ACCOUNT = UUID.fromString("00000001-0001-0001-0001-000000000001");
  private static final AtomicInteger READS = new AtomicInteger();
  private static volatile AccessFailure failure;

  // 两次独立上下文证明策略释放和重建，原生布尔/注解/默认拒绝及快照都走真实MVC。
  @Test
  void commonLocalModeUsesNativeAnnotationsAndRebuilds() throws Exception {
    for (int iteration = 0; iteration < 2; iteration++) {
      failure = null;
      try (var context = start()) {
        var mvc =
            MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
                .build();
        assertEquals(StpLogic.class, context.getBean(StpLogic.class).getClass());
        assertAnonymousAndDefaultDeny(mvc);
        var logic = context.getBean(StpLogic.class);
        String token = logic.createLoginSession(ACCOUNT.toString());
        READS.set(0);
        assertEquals(200, call(mvc, "/permission", token));
        assertEquals(1, READS.get());
        assertNativeProtectionMatrix(mvc, token);
        assertFailuresAndRevocation(mvc, logic, token);
      }
    }
  }

  // 明确匿名、仅登录与未声明入口分别验证，不让后续成功登录掩盖入口错误。
  private static void assertAnonymousAndDefaultDeny(MockMvc mvc) throws Exception {
    assertEquals(200, call(mvc, "/anonymous", null));
    assertEquals(200, call(mvc, "/class-anonymous", null));
    assertEquals(401, call(mvc, "/class-permission", null));
    assertEquals(401, call(mvc, "/login", null));
    assertEquals(403, call(mvc, "/undeclared", null));
  }

  // 方法、类、合成及原生OR/orRole均保留真实允许和拒绝断言。
  private static void assertNativeProtectionMatrix(MockMvc mvc, String token) throws Exception {
    assertEquals(200, call(mvc, "/role", token));
    assertEquals(403, call(mvc, "/denied-role", token));
    assertEquals(200, call(mvc, "/or", token));
    assertEquals(200, call(mvc, "/composed", token));
    assertEquals(200, call(mvc, "/class-permission", token));
    assertEquals(403, call(mvc, "/class-permission-denied", token));
    assertEquals(200, call(mvc, "/permission-or", token));
    assertEquals(403, call(mvc, "/permission-or-denied", token));
    assertEquals(200, call(mvc, "/permission-or-role", token));
    assertEquals(403, call(mvc, "/permission-or-role-denied", token));
  }

  // 同一已签发会话的依赖故障、许可失效和原生退出保持不同失败语义。
  private static void assertFailuresAndRevocation(MockMvc mvc, StpLogic logic, String token)
      throws Exception {
    failure = AccessFailure.UNAVAILABLE;
    assertEquals(503, call(mvc, "/or", token));
    failure = AccessFailure.UNAUTHENTICATED;
    assertEquals(401, call(mvc, "/login", token));
    failure = null;
    logic.logoutByTokenValue(token);
    assertEquals(401, call(mvc, "/login", token));
  }

  // 多值AND必须同时满足全部原生权限／角色，不能被误实现为任一项即可。
  @Test
  void nativeAndRequiresEveryPermissionAndRole() throws Exception {
    failure = null;
    try (var context = start()) {
      var mvc =
          MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
              .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
              .build();
      String token = context.getBean(StpLogic.class).createLoginSession(ACCOUNT.toString());
      assertEquals(200, call(mvc, "/permission-and", token));
      assertEquals(403, call(mvc, "/permission-and-denied", token));
      assertEquals(200, call(mvc, "/role-and", token));
      assertEquals(403, call(mvc, "/role-and-denied", token));
    }
  }

  // 入口完整事实之后的原生Redis读取故障，分别在guard、注解和数据桥接阶段仍为503。
  @Test
  void laterNativeStoreFailuresRemainUnavailable() throws Exception {
    failure = null;
    try (var context = start()) {
      var mvc =
          MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
              .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
              .build();
      var logic = context.getBean(StpLogic.class);
      String token = logic.createLoginSession(ACCOUNT.toString());
      var original = cn.dev33.satoken.SaManager.getSaTokenDao();
      try {
        for (int failAt : new int[] {2, 3, 4}) {
          var reads = new AtomicInteger();
          cn.dev33.satoken.SaManager.setSaTokenDao(
              (cn.dev33.satoken.dao.SaTokenDao)
                  java.lang.reflect.Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {cn.dev33.satoken.dao.SaTokenDao.class},
                      (proxy, method, arguments) -> {
                        if (method.getName().equals("get") && reads.incrementAndGet() == failAt)
                          throw new IllegalStateException("synthetic-native-store-outage");
                        try {
                          return method.invoke(original, arguments);
                        } catch (java.lang.reflect.InvocationTargetException error) {
                          throw error.getCause();
                        }
                      }));
          assertEquals(503, call(mvc, "/permission", token), "native lookup " + failAt);
          assertTrue(reads.get() >= failAt);
        }
      } finally {
        cn.dev33.satoken.SaManager.setSaTokenDao(original);
      }
      assertEquals(200, call(mvc, "/permission", token));
    }
  }

  // 完整事实通过后原生会话失效仍为401，原生异常分类保留且不能拼接实际令牌。
  @Test
  void laterNativeInvalidationNeverExposesTokenInException() throws Exception {
    failure = null;
    try (var context = start()) {
      var mvc =
          MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
              .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
              .build();
      var logic = context.getBean(StpLogic.class);
      String token = logic.createLoginSession(ACCOUNT.toString());
      var original = cn.dev33.satoken.SaManager.getSaTokenDao();
      try {
        for (int invalidAt : new int[] {2, 3}) {
          var reads = new AtomicInteger();
          cn.dev33.satoken.SaManager.setSaTokenDao(
              (cn.dev33.satoken.dao.SaTokenDao)
                  java.lang.reflect.Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {cn.dev33.satoken.dao.SaTokenDao.class},
                      (proxy, method, arguments) -> {
                        if (method.getName().equals("get") && reads.incrementAndGet() == invalidAt)
                          return null;
                        try {
                          return method.invoke(original, arguments);
                        } catch (java.lang.reflect.InvocationTargetException error) {
                          throw error.getCause();
                        }
                      }));
          var result =
              mvc.perform(
                      MockMvcRequestBuilders.get("/permission")
                          .header("Authorization", "Bearer " + token))
                  .andReturn();
          assertEquals(401, result.getResponse().getStatus());
          var error =
              org.junit.jupiter.api.Assertions.assertInstanceOf(
                  cn.dev33.satoken.exception.NotLoginException.class,
                  result.getResolvedException());
          assertEquals("login", error.getLoginType());
          assertEquals(cn.dev33.satoken.exception.NotLoginException.INVALID_TOKEN, error.getType());
          assertFalse(error.getMessage().contains(token));
          assertTrue(reads.get() >= invalidAt);
        }
      } finally {
        cn.dev33.satoken.SaManager.setSaTokenDao(original);
      }
    }
  }

  // 控制器程序式调用原生提供者时已离开preHandle，桥接自身仍负责安全的原生读取边界。
  @Test
  void programmaticProviderReadSanitizesInvalidationAndOutage() throws Exception {
    failure = null;
    try (var context = start()) {
      var mvc =
          MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
              .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
              .build();
      String token = context.getBean(StpLogic.class).createLoginSession(ACCOUNT.toString());
      var original = cn.dev33.satoken.SaManager.getSaTokenDao();
      try {
        for (boolean unavailable : new boolean[] {false, true}) {
          var reads = new AtomicInteger();
          cn.dev33.satoken.SaManager.setSaTokenDao(
              providerReadFailure(original, reads, unavailable));
          var result =
              mvc.perform(
                      MockMvcRequestBuilders.get("/programmatic-provider")
                          .header("Authorization", "Bearer " + token))
                  .andReturn();
          assertProgrammaticFailure(result, token, unavailable);
          assertEquals(4, reads.get());
        }
      } finally {
        cn.dev33.satoken.SaManager.setSaTokenDao(original);
      }
    }
  }

  // 首次source、guard及原生登录检查成功后，仅提供者内部第四次真实原生读取发生故障。
  private static cn.dev33.satoken.dao.SaTokenDao providerReadFailure(
      cn.dev33.satoken.dao.SaTokenDao original, AtomicInteger reads, boolean unavailable) {
    return (cn.dev33.satoken.dao.SaTokenDao)
        java.lang.reflect.Proxy.newProxyInstance(
            NativeAccessIntegrationTest.class.getClassLoader(),
            new Class<?>[] {cn.dev33.satoken.dao.SaTokenDao.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("get") && reads.incrementAndGet() == 4) {
                if (unavailable)
                  throw new IllegalStateException("synthetic-native-provider-outage");
                return null;
              }
              try {
                return method.invoke(original, arguments);
              } catch (java.lang.reflect.InvocationTargetException error) {
                throw error.getCause();
              }
            });
  }

  // 故障分别保留原生401分类与公共503，不依赖统一失败或含令牌的厂商异常。
  private static void assertProgrammaticFailure(
      org.springframework.test.web.servlet.MvcResult result, String token, boolean unavailable) {
    assertEquals(unavailable ? 503 : 401, result.getResponse().getStatus());
    if (unavailable) {
      var error =
          org.junit.jupiter.api.Assertions.assertInstanceOf(
              AccessException.class, result.getResolvedException());
      assertEquals(AccessFailure.UNAVAILABLE, error.failure());
    } else {
      var error =
          org.junit.jupiter.api.Assertions.assertInstanceOf(
              cn.dev33.satoken.exception.NotLoginException.class, result.getResolvedException());
      assertEquals("login", error.getLoginType());
      assertEquals(cn.dev33.satoken.exception.NotLoginException.INVALID_TOKEN, error.getType());
      assertFalse(error.getMessage().contains(token));
    }
  }

  // 无法被原生执行的子类策略、空声明、匿名混合及非支持分支都必须拒绝启动。
  @Test
  void unsupportedDeclarationsFailAtStartup() {
    for (var invalid :
        java.util.List.of(
            InheritedProtected.class,
            EmptyPermission.class,
            AnonymousMixed.class,
            UnsupportedSafe.class,
            EmptyOr.class,
            NestedOtherType.class))
      assertThrows(
          RuntimeException.class,
          () -> {
            try (var ignored = start(invalid)) {}
          });
  }

  // 缺少原生执行器或宿主替换解析策略不能把“有声明”变成仅登录。
  @Test
  void modifiedNativeStrategiesFailAtStartup() {
    var strategy = cn.dev33.satoken.strategy.SaAnnotationStrategy.instance;
    var handler = strategy.annotationHandlerMap.remove(SaCheckPermission.class);
    try {
      assertThrows(
          RuntimeException.class,
          () -> {
            try (var ignored = start()) {}
          });
    } finally {
      strategy.annotationHandlerMap.put(SaCheckPermission.class, handler);
    }
    var previous = strategy.getAnnotation;
    strategy.getAnnotation = (element, kind) -> null;
    try {
      assertThrows(
          RuntimeException.class,
          () -> {
            try (var ignored = start()) {}
          });
    } finally {
      strategy.getAnnotation = previous;
    }
    try (var ignored = start()) {
      assertSame(ignored.getBean(StpLogic.class), StpUtil.getStpLogic());
    }
  }

  // 未支持分支的宿主处理器不限制正常接入，但其声明不能因处理器缺失或替换而漏检。
  @Test
  void unusedUnsupportedHandlersDoNotRestrictSupportedAccess() throws Exception {
    var handlers = cn.dev33.satoken.strategy.SaAnnotationStrategy.instance.annotationHandlerMap;
    var unsupported =
        java.util.List.of(
            SaCheckSafe.class,
            SaCheckHttpBasic.class,
            SaCheckHttpDigest.class,
            SaCheckDisable.class);
    var previous = new java.util.HashMap<>(handlers);
    try {
      for (boolean replace : new boolean[] {false, true}) {
        for (var kind : unsupported) {
          if (replace) handlers.put(kind, previous.get(SaCheckLogin.class));
          else handlers.remove(kind);
        }
        failure = null;
        try (var context = start()) {
          var mvc =
              MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                  .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
                  .build();
          var logic = context.getBean(StpLogic.class);
          assertEquals(200, call(mvc, "/permission", logic.createLoginSession(ACCOUNT.toString())));
          assertEquals(401, call(mvc, "/permission", null));
        }
        for (var invalid :
            java.util.List.of(
                UnsupportedSafe.class,
                UnsupportedBasic.class,
                UnsupportedDigest.class,
                UnsupportedDisable.class,
                ComposedUnsupportedSafe.class,
                PermissionWithUnsupportedSafe.class))
          assertThrows(
              RuntimeException.class,
              () -> {
                try (var ignored = start(invalid)) {}
              });
      }
    } finally {
      handlers.clear();
      handlers.putAll(previous);
    }
  }

  // 第二上下文在普通单例注入前失败，不能覆盖第一上下文原生逻辑或权限提供者。
  @Test
  void secondContextCannotReplaceRunningNativeOwner() throws Exception {
    failure = null;
    try (var first = start()) {
      var logic = first.getBean(StpLogic.class);
      var provider = cn.dev33.satoken.SaManager.getStpInterface();
      assertThrows(
          RuntimeException.class,
          () -> {
            try (var ignored = start()) {}
          });
      assertSame(logic, StpUtil.getStpLogic());
      assertSame(logic, cn.dev33.satoken.SaManager.getStpLogic("login"));
      assertSame(provider, cn.dev33.satoken.SaManager.getStpInterface());
      var mvc =
          MockMvcBuilders.webAppContextSetup((WebApplicationContext) first)
              .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
              .build();
      assertEquals(200, call(mvc, "/login", logic.createLoginSession(ACCOUNT.toString())));
    }
  }

  private static org.springframework.context.ConfigurableApplicationContext start(
      Class<?>... extra) {
    var sources = new java.util.ArrayList<Class<?>>();
    sources.add(Application.class);
    sources.addAll(java.util.List.of(extra));
    return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
        .run(
            "--server.port=0",
            "--spring.main.banner-mode=off",
            "--logging.level.root=ERROR",
            "--sa-token.token-name=Authorization",
            "--sa-token.token-prefix=Bearer",
            "--sa-token.is-read-cookie=false",
            "--sa-token.is-read-body=false",
            "--sa-token.is-log=false");
  }

  private static int call(MockMvc mvc, String path, String token) throws Exception {
    var request = MockMvcRequestBuilders.get(path);
    if (token != null) request.header("Authorization", "Bearer " + token);
    return mvc.perform(request).andReturn().getResponse().getStatus();
  }

  /** 同组件本地应用，不提供远端URL/TLS输入或连接池资源。 */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    LocalAccessConfiguration.class,
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
            "project-alpha",
            UUID.fromString(id.toString()),
            Set.of("account:read", "account:update"),
            Set.of("manager", "auditor"));
      };
    }
  }

  /** 原生合成注解夹具，仍由同一SaAnnotationStrategy执行。 */
  @Retention(RetentionPolicy.RUNTIME)
  @SaCheckPermission("account:read")
  @interface ReadAccount {}

  /** 固定原生机制端点覆盖，不包含真实组织或角色业务。 */
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

    @ReadAccount
    @GetMapping("/composed")
    String composed() {
      return "composed";
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
      cn.dev33.satoken.SaManager.getStpInterface().getPermissionList(ACCOUNT.toString(), "login");
      return "provider";
    }

    @GetMapping("/undeclared")
    String undeclared() {
      throw new AssertionError("未声明入口不得执行");
    }
  }

  /** 只有类声明的权限仍由原生执行，方法无需重复注解。 */
  @RestController
  @SaCheckPermission("account:read")
  static class ClassPermissionEndpoint {
    @GetMapping("/class-permission")
    String current() {
      return "class-permission";
    }
  }

  /** 纯类权限不足不能因方法没有重复声明而允许。 */
  @RestController
  @SaCheckPermission("missing")
  static class ClassDeniedPermissionEndpoint {
    @GetMapping("/class-permission-denied")
    String current() {
      return "class-permission-denied";
    }
  }

  /** 纯类匿名声明覆盖本类普通方法，同时保留原生匿名布尔语义。 */
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

  /** 继承方法本身没有策略，子类策略不能被原生遗漏执行。 */
  static class ParentEndpoint {
    @GetMapping("/inherited")
    public String inherited() {
      return "unsafe";
    }
  }

  /** 原生使用方法声明类，拒绝无法覆盖的子类独有策略。 */
  @RestController
  @SaCheckPermission("missing")
  static class InheritedProtected extends ParentEndpoint {}

  /** 空权限不是登录声明。 */
  @RestController
  static class EmptyPermission {
    @SaCheckPermission({})
    @GetMapping("/empty")
    String value() {
      return "x";
    }
  }

  /** 匿名和保护不能依赖隐式覆盖顺序。 */
  @RestController
  @SaIgnore
  static class AnonymousMixed {
    @SaCheckLogin
    @GetMapping("/mixed")
    String value() {
      return "x";
    }
  }

  /** 首批不支持二级安全认证分支。 */
  @RestController
  static class UnsupportedSafe {
    @SaCheckSafe
    @GetMapping("/safe")
    String value() {
      return "x";
    }
  }

  /** 未支持的Basic声明也不依赖宿主处理器是否登记。 */
  @RestController
  static class UnsupportedBasic {
    @SaCheckHttpBasic
    @GetMapping("/basic")
    String value() {
      return "x";
    }
  }

  /** 未支持的Digest声明也不依赖宿主处理器是否登记。 */
  @RestController
  static class UnsupportedDigest {
    @SaCheckHttpDigest
    @GetMapping("/digest")
    String value() {
      return "x";
    }
  }

  /** 未支持的服务封禁声明不能退化成普通登录声明。 */
  @RestController
  static class UnsupportedDisable {
    @SaCheckDisable("unsupported")
    @GetMapping("/disable")
    String value() {
      return "x";
    }
  }

  /** 合成声明与直接声明具有相同的支持边界。 */
  @Retention(RetentionPolicy.RUNTIME)
  @SaCheckSafe
  @interface ExtraSafety {}

  /** 不支持的合成声明必须在启动时拒绝。 */
  @RestController
  static class ComposedUnsupportedSafe {
    @ExtraSafety
    @GetMapping("/composed-safe")
    String value() {
      return "x";
    }
  }

  /** 受支持权限与不支持声明混合也不能只执行前者。 */
  @RestController
  static class PermissionWithUnsupportedSafe {
    @SaCheckPermission("iam:account:read")
    @SaCheckSafe
    @GetMapping("/permission-safe")
    String value() {
      return "x";
    }
  }

  /** 空OR不能构成任何真实访问策略。 */
  @RestController
  static class EmptyOr {
    @SaCheckOr
    @GetMapping("/empty-or")
    String value() {
      return "x";
    }
  }

  /** 嵌套类型约束与直接声明完全一致。 */
  @RestController
  static class NestedOtherType {
    @SaCheckOr(login = @SaCheckLogin(type = "other"))
    @GetMapping("/other")
    String value() {
      return "x";
    }
  }
}
