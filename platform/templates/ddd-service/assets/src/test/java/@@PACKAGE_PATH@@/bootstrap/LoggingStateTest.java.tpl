package @@PACKAGE@@.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 验证日志指标与健康的降级表达；真实阻塞与恢复另由产品场景验证。 */
class LoggingStateTest {
  /** 测试环境没有模板异步出口时不能报告观测完全正常。 */
  @Test
  void missingAsyncSinkIsVisibleWithoutBlocking() {
    var context =
        (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var original = root.getAppender("ASYNC");
    if (original != null) {
      root.detachAppender(original);
    }
    try {
      var state = new LoggingState();
      var registry = new SimpleMeterRegistry();
      state.bindTo(registry);
      assertEquals("DEGRADED", state.health().getStatus().getCode());
      assertEquals(1.0, registry.get("domainforge.logging.degraded").gauge().value());
    } finally {
      if (original != null) {
        root.addAppender(original);
      }
    }
  }
}
