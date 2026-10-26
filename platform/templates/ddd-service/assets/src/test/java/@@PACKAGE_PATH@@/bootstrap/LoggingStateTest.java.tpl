package @@PACKAGE@@.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未装配异步出口时必须报告降级，不能默认健康。
 */
class LoggingStateTest {
    @Test
    void missingAsyncAppenderIsDegraded() {
        assertTrue(new LoggingState().degraded());
    }
}
