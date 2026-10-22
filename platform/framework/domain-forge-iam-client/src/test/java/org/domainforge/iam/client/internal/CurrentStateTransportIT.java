package org.domainforge.iam.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 原生HTTPS故障、协议和恢复测试；必要测试证书缺失时失败，不默默跳过。 */
class CurrentStateTransportIT {
  private static HttpsServer server;
  private static ExecutorService executor;
  private static ControlledIdentityEndpoint endpoint;
  private static org.domainforge.access.api.CurrentSubjectSource client;
  private static org.springframework.context.ConfigurableApplicationContext context;
  private static volatile boolean secureDiscovery = true;
  private static volatile int discoveryPort;

  // 使用外部临时脚本签发的localhost证书、私钥和唯一信任库，服务端要求客户端证书。
  @BeforeAll
  static void start() throws Exception {
    assertNotNull(System.getenv("ACCESS_TEST_STORE"));
    var keys = java.security.KeyStore.getInstance("PKCS12");
    var trust = java.security.KeyStore.getInstance("PKCS12");
    String password = System.getenv("ACCESS_TEST_PASSWORD");
    try (var input =
        java.nio.file.Files.newInputStream(Path.of(System.getenv("ACCESS_TEST_STORE")))) {
      keys.load(input, password.toCharArray());
    }
    try (var input =
        java.nio.file.Files.newInputStream(Path.of(System.getenv("ACCESS_TEST_TRUST")))) {
      trust.load(input, password.toCharArray());
    }
    var tls =
        org.springframework.boot.ssl.SslBundle.of(
                org.springframework.boot.ssl.SslStoreBundle.of(keys, password, trust))
            .createSslContext();
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new RequiredClientTls(tls));
    endpoint = new ControlledIdentityEndpoint();
    server.createContext("/", endpoint);
    executor = Executors.newFixedThreadPool(4);
    server.setExecutor(executor);
    server.start();
    discoveryPort = server.getAddress().getPort();
    context = startApplication();
    client = context.getBean(org.domainforge.access.api.CurrentSubjectSource.class);
    recover();
  }

  private static org.springframework.context.ConfigurableApplicationContext startApplication(
      String... overrides) {
    String password = System.getenv("ACCESS_TEST_PASSWORD");
    var arguments =
        new java.util.ArrayList<String>(
            java.util.List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--sa-token.is-print=false",
                "--spring.cloud.openfeign.client.config.default.loggerLevel=full",
                "--spring.cloud.openfeign.client.config.default.connectTimeout=5000",
                "--spring.cloud.openfeign.client.config.default.readTimeout=5000",
                "--spring.cloud.loadbalancer.cache.enabled=false",
                "--spring.cloud.loadbalancer.retry.enabled=false",
                "--spring.cloud.loadbalancer.service-discovery.timeout=200ms",
                "--domainforge.iam-client.project-id=project-alpha",
                "--spring.ssl.bundle.jks.iam-client.keystore.location=file:"
                    + System.getenv("ACCESS_TEST_STORE"),
                "--spring.ssl.bundle.jks.iam-client.keystore.password=" + password,
                "--spring.ssl.bundle.jks.iam-client.keystore.type=PKCS12",
                "--spring.ssl.bundle.jks.iam-client.truststore.location=file:"
                    + System.getenv("ACCESS_TEST_TRUST"),
                "--spring.ssl.bundle.jks.iam-client.truststore.password=" + password,
                "--spring.ssl.bundle.jks.iam-client.truststore.type=PKCS12"));
    arguments.addAll(java.util.List.of(overrides));
    return new org.springframework.boot.builder.SpringApplicationBuilder(RemoteApplication.class)
        .run(arguments.toArray(String[]::new));
  }

  @AfterAll
  static void close() throws Exception {
    if (context != null) context.close();
    if (server != null) server.stop(0);
    if (executor != null) executor.shutdownNow();
  }

  // 同一客户端在每种畸形响应后都须恢复接受有效协议；不允许统一拒绝代替正确解码。
  @Test
  void malformedResponsesRejectAndValidResponsesRecover() {
    for (String invalid :
        new String[] {
          ControlledIdentityEndpoint.VALID.replace("project-alpha", "project-beta"),
          ControlledIdentityEndpoint.VALID.replace(
              "00000001-0001-0001-0001-000000000001", "1-1-1-1-1"),
          ControlledIdentityEndpoint.VALID + "{}",
          ControlledIdentityEndpoint.VALID.replace(",\"roles\":[]", ""),
          ControlledIdentityEndpoint.VALID.replace(
              "\"roles\":[]", "\"roles\":[\"manager\",\"manager\"]"),
          ControlledIdentityEndpoint.VALID.replace("\"roles\":[]", "\"roles\":[\"*\"]"),
          ControlledIdentityEndpoint.VALID.replace("\"roles\":[]", "\"roles\":[],\"other\":true"),
          ControlledIdentityEndpoint.VALID.replace("\"code\":", "\"code\":\"SUCCESS\",\"code\":"),
          "x".repeat(16385)
        }) {
      endpoint.body = invalid;
      reject(AccessFailure.UNAVAILABLE);
      recover();
    }
    endpoint.media = "application/json-unsupported";
    reject(AccessFailure.UNAVAILABLE);
    endpoint.media = "application/json";
    recover();
  }

  // 原生401先按身份失败映射，不依赖错误体；重定向不能转发Bearer，服务端只收到一次调用。
  @Test
  void statusDoesNotRetryOrFollowRedirect() {
    for (int status : new int[] {401, 302, 503}) {
      endpoint.status = status;
      endpoint.body = "not-json";
      int before = endpoint.requests.get();
      reject(status == 401 ? AccessFailure.UNAUTHENTICATED : AccessFailure.UNAVAILABLE);
      assertEquals(before + 1, endpoint.requests.get());
      endpoint.status = 200;
      recover();
    }
  }

  // 可选登录只吞身份失效；故障仍报告不可用，之后的新请求能恢复且不能复用旧允许状态。
  @Test
  void optionalLookupDistinguishesInvalidIdentityFromOutage() {
    StpLogic previous = StpUtil.getStpLogic();
    var adapter = context.getBean(StpLogic.class);
    try {
      for (int status : new int[] {200, 401, 503, 200}) {
        SaTokenContextMockUtil.setMockContext();
        adapter.setTokenValue("synthetic-native-token");
        endpoint.status = status;
        endpoint.body = status == 200 ? ControlledIdentityEndpoint.VALID : "malformed-error";
        if (status == 200) {
          assertEquals("00000001-0001-0001-0001-000000000001", adapter.getLoginIdDefaultNull());
          assertTrue(adapter.isLogin());
          assertTrue(adapter.hasPermission("iam:account:read"));
          assertFalse(adapter.hasPermission("iam:grant:manage"));
        } else if (status == 401) {
          assertNull(adapter.getLoginIdDefaultNull());
          assertFalse(adapter.isLogin());
          assertThrows(NotLoginException.class, adapter::getLoginId);
        } else {
          assertEquals(
              AccessFailure.UNAVAILABLE,
              assertThrows(AccessException.class, adapter::getLoginIdDefaultNull).failure());
          assertEquals(
              AccessFailure.UNAVAILABLE,
              assertThrows(AccessException.class, adapter::isLogin).failure());
        }
        SaTokenContextMockUtil.clearContext();
      }
    } finally {
      endpoint.status = 200;
      endpoint.body = ControlledIdentityEndpoint.VALID;
      SaTokenContextMockUtil.clearContext();
      StpUtil.setStpLogic(previous);
    }
  }

  // 响应头成功但响应体停滞时由原生读取超时拒绝，随后可重新取得连接。
  @Test
  void nativeReadTimeoutRejectsStalledBodyAndRecovers() throws Exception {
    endpoint.delay = 1500;
    reject(AccessFailure.UNAVAILABLE);
    endpoint.delay = 0;
    Thread.sleep(550);
    recover();
  }

  // 真实HTTPS事实源与本地模式复用同一公共原生执行链，无IAM数据库或Redis输入。
  @Test
  void remoteModeUsesSameNativePipeline() throws Exception {
    endpoint.status = 200;
    endpoint.body = ControlledIdentityEndpoint.VALID;
    try {
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                  (org.springframework.web.context.WebApplicationContext) context)
              .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
              .build();
      org.junit.jupiter.api.Assertions.assertSame(
          context.getBean(StpLogic.class), StpUtil.getStpLogic());
      int before = endpoint.requests.get();
      var request =
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/remote")
              .header("Authorization", "Bearer synthetic-native-token");
      assertEquals(200, mvc.perform(request).andReturn().getResponse().getStatus());
      assertEquals(before + 1, endpoint.requests.get());
      endpoint.status = 503;
      assertEquals(503, mvc.perform(request).andReturn().getResponse().getStatus());
      endpoint.status = 401;
      assertEquals(401, mvc.perform(request).andReturn().getResponse().getStatus());
      endpoint.status = 200;
      assertEquals(200, mvc.perform(request).andReturn().getResponse().getStatus());
    } finally {
      endpoint.status = 200;
      endpoint.body = ControlledIdentityEndpoint.VALID;
    }
  }

  /** 真实远程模式消费应用，只引用公共接入组件。 */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  @org.springframework.context.annotation.Import({
    org.domainforge.access.backend.configuration.BackendAccessConfiguration.class,
    org.domainforge.iam.client.configuration.IamClientConfiguration.class,
    RemoteEndpoints.class,
    DiscoveryConfiguration.class
  })
  static class RemoteApplication {}

  /** 可控的原生发现结果；真实 Nacos 组合由产品运行验证另证。 */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class DiscoveryConfiguration {
    @org.springframework.context.annotation.Bean
    feign.RequestInterceptor unrelatedHostAuthentication() {
      return request -> request.header("Authorization", "Bearer unrelated-host-credential");
    }

    @org.springframework.context.annotation.Bean
    org.springframework.cloud.client.discovery.DiscoveryClient controlledDiscovery() {
      return new org.springframework.cloud.client.discovery.DiscoveryClient() {
        @Override
        public int getOrder() {
          return -100;
        }

        @Override
        public String description() {
          return "controlled TLS identity fixture";
        }

        @Override
        public java.util.List<org.springframework.cloud.client.ServiceInstance> getInstances(
            String id) {
          return java.util.List.of(
              new org.springframework.cloud.client.DefaultServiceInstance(
                  "iam-native-test", "iam", "localhost", discoveryPort, secureDiscovery));
        }

        @Override
        public java.util.List<String> getServices() {
          return java.util.List.of("iam");
        }
      };
    }
  }

  // IAM 专属原生配置只位于命名Feign子上下文，宿主拦截器不能污染认证头。
  @Test
  void isolatesNativeConfigurationAndHostAuthentication() {
    for (String name :
        new String[] {"iamHttpClient", "iamFeignClient", "iamOptions", "iamRetryer", "iamLogLevel"})
      assertFalse(context.containsLocalBean(name), name + " 不能泄露至宿主上下文");
    var factory = context.getBean(org.springframework.cloud.openfeign.FeignClientFactory.class);
    var options = factory.getInstance("domainForgeIamAccess", feign.Request.Options.class);
    var transport =
        factory.getInstance(
            "domainForgeIamAccess",
            org.apache.hc.client5.http.impl.classic.CloseableHttpClient.class);
    assertEquals(
        200,
        ((org.apache.hc.client5.http.config.Configurable) transport)
            .getConfig()
            .getConnectionRequestTimeout()
            .toMilliseconds());
    assertEquals(300, options.connectTimeoutMillis());
    assertEquals(1000, options.readTimeoutMillis());
    assertFalse(options.isFollowRedirects());
    assertEquals(
        feign.Retryer.NEVER_RETRY,
        factory.getInstance("domainForgeIamAccess", feign.Retryer.class));
    client.resolve("synthetic-isolation-token");
    assertEquals("Bearer synthetic-isolation-token", endpoint.authorization);
  }

  // 原生HTTP/1逐行／字段数量边界在解码之前生效，恢复不能被统一拒绝替代。
  @Test
  void nativeHeaderBoundsRejectAndRecover() {
    endpoint.largeHeader = 9000;
    try {
      reject(AccessFailure.UNAVAILABLE);
    } finally {
      endpoint.largeHeader = 0;
    }
    recover();
    endpoint.headerCount = 101;
    try {
      reject(AccessFailure.UNAVAILABLE);
    } finally {
      endpoint.headerCount = 0;
    }
    recover();
  }

  // 负载均衡最终选中明文地址时，在该地址收到任何Bearer之前拒绝。
  @Test
  void rejectsDiscoveredHttpTargetBeforeSendingBearer() throws Exception {
    var plaintext =
        com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var rejectedTarget = new ControlledIdentityEndpoint();
    plaintext.createContext("/", rejectedTarget);
    plaintext.start();
    try {
      secureDiscovery = false;
      discoveryPort = plaintext.getAddress().getPort();
      reject(AccessFailure.UNAVAILABLE);
      assertEquals(0, rejectedTarget.requests.get());
      assertNull(rejectedTarget.authorization);
    } finally {
      secureDiscovery = true;
      discoveryPort = server.getAddress().getPort();
      plaintext.stop(0);
    }
    recover();
  }

  // 最终目标校验位于原生负载均衡重建URL之后，非法接口不能到达TLS端点。
  @Test
  void rejectsUnexpectedEndpointBeforeSendingBearer() throws Exception {
    var factory = context.getBean(org.springframework.cloud.openfeign.FeignClientFactory.class);
    var transport = factory.getInstance("domainForgeIamAccess", feign.Client.class);
    var options = factory.getInstance("domainForgeIamAccess", feign.Request.Options.class);
    for (String uri :
        new String[] {
          "http://iam/other",
          "http://iam/internal/access/current?other=true",
          "http://iam/internal/access/current#fragment",
          "http://other@iam/internal/access/current"
        }) {
      var request = endpointRequest(feign.Request.HttpMethod.GET, uri);
      int before = endpoint.requests.get();
      assertEquals(
          AccessFailure.UNAVAILABLE,
          assertThrows(AccessException.class, () -> transport.execute(request, options), uri)
              .failure());
      assertEquals(before, endpoint.requests.get());
    }
    var post = endpointRequest(feign.Request.HttpMethod.POST, "http://iam/internal/access/current");
    int before = endpoint.requests.get();
    assertEquals(
        AccessFailure.UNAVAILABLE,
        assertThrows(AccessException.class, () -> transport.execute(post, options)).failure());
    assertEquals(before, endpoint.requests.get());
    recover();
  }

  // 原生URL覆盖会解包负载均衡客户端；仍须经过唯一目标校验，不构成新的公开消费方式。
  @Test
  void nativeUrlOverridesCannotBypassEndpointValidation() throws Exception {
    var plaintext =
        com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var rejectedTarget = new ControlledIdentityEndpoint();
    plaintext.createContext("/", rejectedTarget);
    plaintext.start();
    context.close();
    context = null;
    String tlsBase = "https://localhost:" + server.getAddress().getPort();
    try {
      for (String url :
          new String[] {
            "http://localhost:" + plaintext.getAddress().getPort(), tlsBase + "/other"
          }) {
        int before = endpoint.requests.get();
        try (var direct =
            startApplication(
                "--spring.cloud.openfeign.client.config.domainForgeIamAccess.url=" + url)) {
          AccessException rejected = null;
          try {
            direct
                .getBean(org.domainforge.access.api.CurrentSubjectSource.class)
                .resolve("synthetic-native-override-token");
          } catch (AccessException failure) {
            rejected = failure;
          }
          assertEquals(0, rejectedTarget.requests.get(), "原生URL覆盖不得向HTTP地址发送Bearer");
          assertEquals(before, endpoint.requests.get(), "原生URL覆盖不得访问错误接口");
          assertNotNull(rejected);
          assertEquals(AccessFailure.UNAVAILABLE, rejected.failure());
        }
      }
      try (var direct =
          startApplication(
              "--spring.cloud.openfeign.client.config.domainForgeIamAccess.url=" + tlsBase)) {
        assertEquals(
            "project-alpha",
            direct
                .getBean(org.domainforge.access.api.CurrentSubjectSource.class)
                .resolve("synthetic-native-override-token")
                .projectId());
        assertEquals("Bearer synthetic-native-override-token", endpoint.authorization);
      }
    } finally {
      plaintext.stop(0);
      context = startApplication();
      client = context.getBean(org.domainforge.access.api.CurrentSubjectSource.class);
    }
    recover();
  }

  private static feign.Request endpointRequest(feign.Request.HttpMethod method, String uri) {
    return feign.Request.create(
        method,
        uri,
        java.util.Map.of("Authorization", java.util.List.of("Bearer synthetic-endpoint-token")),
        null,
        java.nio.charset.StandardCharsets.UTF_8,
        null);
  }

  /** 同请求原生权限与公开主体必须复用唯一远端完整事实。 */
  @org.springframework.web.bind.annotation.RestController
  static class RemoteEndpoints {
    private final org.domainforge.access.api.CurrentAccess current;

    RemoteEndpoints(org.domainforge.access.api.CurrentAccess current) {
      this.current = current;
    }

    @cn.dev33.satoken.annotation.SaCheckPermission("iam:account:read")
    @org.springframework.web.bind.annotation.GetMapping("/remote")
    String current() {
      assertEquals("project-alpha", current.current().projectId());
      assertTrue(StpUtil.hasPermission("iam:account:read"));
      assertFalse(StpUtil.hasRole("manager"));
      return "current";
    }
  }

  private static void reject(AccessFailure expected) {
    var error =
        assertThrows(AccessException.class, () -> client.resolve("synthetic-transport-token"));
    assertEquals(expected, error.failure());
  }

  private static void recover() {
    endpoint.body = ControlledIdentityEndpoint.VALID;
    assertEquals("project-alpha", client.resolve("synthetic-transport-token").projectId());
  }
}
