package org.domainforge.access.backend.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpLogic;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.api.CurrentSubject;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.access.backend.configuration.BackendAccessConfiguration;
import org.domainforge.access.backend.configuration.LocalAccessConfiguration;
import org.domainforge.access.backend.internal.remote.BackendStpLogic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/** 真实Boot装配对照；来源仅为机制夹具，不替代远程协议或IAM存储验证。 */
class BackendAccessConfigurationTest {
  private static final UUID ACCOUNT = UUID.fromString("00000001-0001-0001-0001-000000000001");
  private static final AtomicInteger READS = new AtomicInteger();
  private static volatile AccessFailure failure;

  // 业务应用提供唯一来源即可接入；没有固定URL/TLS配置，同请求鉴权和主体只取一次。
  @Test
  void backendUsesExternalSourceWithoutTransportConfiguration() throws Exception {
    failure = null;
    try (var context = start(RemoteApplication.class)) {
      assertEquals(BackendStpLogic.class, context.getBean(StpLogic.class).getClass());
      var mvc = mvc(context);
      READS.set(0);
      assertEquals(200, call(mvc, "external-source-token"));
      assertEquals(1, READS.get());
      failure = AccessFailure.UNAVAILABLE;
      assertEquals(503, call(mvc, "external-source-token"));
      failure = AccessFailure.UNAUTHENTICATED;
      assertEquals(401, call(mvc, "external-source-token"));
      failure = null;
      assertEquals(200, call(mvc, "external-source-token"));
    }
  }

  // 缺失事实源必须因依赖未满足而启动失败，不能静默装配固定远端或允许来源。
  @Test
  void backendWithoutSourceFailsStartup() {
    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> start(MissingSourceApplication.class));
    Throwable cause = failure;
    while (cause.getCause() != null) cause = cause.getCause();
    assertTrue(cause instanceof NoSuchBeanDefinitionException);
    assertTrue(cause.getMessage().contains(CurrentSubjectSource.class.getName()));
  }

  // 本地模式继续采用普通原生会话逻辑，没有任何远程输入仍可启动及执行鉴权。
  @Test
  void localModeKeepsNativeSessionsWithoutTransportConfiguration() throws Exception {
    failure = null;
    try (var context = start(LocalApplication.class)) {
      var logic = context.getBean(StpLogic.class);
      assertEquals(StpLogic.class, logic.getClass());
      String token = logic.createLoginSession(ACCOUNT.toString());
      assertEquals(200, call(mvc(context), token));
      logic.logoutByTokenValue(token);
      assertEquals(401, call(mvc(context), token));
    }
  }

  private static ConfigurableApplicationContext start(Class<?> application) {
    return new SpringApplicationBuilder(application)
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

  private static MockMvc mvc(ConfigurableApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
        .addFilters(new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet())
        .build();
  }

  private static int call(MockMvc mvc, String token) throws Exception {
    return mvc.perform(
            MockMvcRequestBuilders.get("/source-backed").header("Authorization", "Bearer " + token))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** 应用只显式选择远端身份读取模式，运输由外部来源负责。 */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({BackendAccessConfiguration.class, SourceFixture.class, Endpoint.class})
  static class RemoteApplication {}

  /** 缺源反例不提供隐式默认身份。 */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(BackendAccessConfiguration.class)
  static class MissingSourceApplication {}

  /** 本地装配保持不变，与远程应用共用执行合同。 */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({LocalAccessConfiguration.class, SourceFixture.class, Endpoint.class})
  static class LocalApplication {}

  /** 固定账号的受控来源只验证装配、调用次数和失败分类。 */
  @Configuration(proxyBeanMethods = false)
  static class SourceFixture {
    @Bean
    CurrentSubjectSource source() {
      return token -> {
        READS.incrementAndGet();
        if (failure != null) throw new AccessException(failure);
        return new CurrentSubject("project-alpha", ACCOUNT, Set.of("account:read"));
      };
    }
  }

  /** 原生注解和业务入口共同读取同一个请求内完整主体。 */
  @RestController
  static class Endpoint {
    private final CurrentAccess current;

    Endpoint(CurrentAccess current) {
      this.current = current;
    }

    @SaCheckPermission("account:read")
    @GetMapping("/source-backed")
    String read() {
      assertEquals(ACCOUNT, current.current().accountId());
      return "current";
    }
  }
}
