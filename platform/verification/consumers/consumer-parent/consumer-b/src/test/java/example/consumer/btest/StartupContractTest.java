package example.consumer.btest;

import static org.junit.jupiter.api.Assertions.*;

import example.consumer.b.ConsumerApplication;
import example.consumer.b.ConsumerRejection;
import example.consumer.b.RejectionAdapter;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;
import org.domainforge.web.api.PlatformJsonApi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class StartupContractTest {
  @Test
  void defaultStartupIncludesActuatorWithoutAmbiguousMapping() {
    SpringApplication app = new SpringApplication(ConsumerApplication.class);
    try (var context = app.run("--server.port=0", "--logging.level.root=ERROR")) {
      assertTrue(context.isActive());
      assertEquals(1, context.getBeansOfType(RejectionAdapter.class).size());
    }
  }

  @Test
  void narrowedOverrideDoesNotCompeteWithPlatformAdvice() {
    SpringApplication app = new SpringApplication(ConsumerApplication.class, NarrowAdvice.class);
    try (var context = app.run("--server.port=0", "--logging.level.root=ERROR")) {
      assertTrue(context.isActive());
    }
  }

  @Test
  void duplicateMappingFailsActualStartup() {
    assertStartupRejected(Duplicate.class, "平台拒绝映射类型重复");
  }

  @Test
  void broadMappingFailsActualStartup() {
    assertStartupRejected(Broad.class, "平台拒绝映射必须声明自有具体异常类型");
  }

  @Test
  void competingAdviceFailsActualStartup() {
    assertStartupRejected(Conflict.class, "平台 JSON 入口存在竞争的兜底 Advice");
  }

  // 只注册本场景配置；测试包位于宿主扫描范围之外，防止负例互相污染。
  private static void assertStartupRejected(Class<?> configuration, String expected) {
    SpringApplication app = new SpringApplication(ConsumerApplication.class, configuration);
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> {
              try (var context = app.run("--server.port=0", "--logging.level.root=ERROR")) {
                fail("非法接入不能启动成功");
              }
            });
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    assertTrue(cause.getMessage().contains(expected), cause.toString());
  }

  static class Duplicate {
    @Bean
    BusinessRejectionMapping<ConsumerRejection> duplicateMapping() {
      return new RejectionAdapter();
    }
  }

  static class Broad {
    @Bean
    BusinessRejectionMapping<RuntimeException> broadMapping() {
      return new BroadMapping();
    }
  }

  static class BroadMapping implements BusinessRejectionMapping<RuntimeException> {
    public Class<RuntimeException> exceptionType() {
      return RuntimeException.class;
    }

    public ApiResponse<Void> map(RuntimeException failure) {
      return ApiResponse.rejected("DECLINED", "拒绝");
    }
  }

  static class BroadAdvice {
    @ExceptionHandler(Exception.class)
    String swallow(Exception failure) {
      return "base";
    }
  }

  @RestControllerAdvice(annotations = PlatformJsonApi.class)
  static class NarrowAdvice extends BroadAdvice {
    @Override
    @ExceptionHandler(IllegalArgumentException.class)
    String swallow(Exception failure) {
      return "specific";
    }
  }

  @RestControllerAdvice(annotations = PlatformJsonApi.class)
  static class Conflict {
    @ExceptionHandler(Exception.class)
    String swallow(Exception failure) {
      return "错误成功";
    }
  }
}
