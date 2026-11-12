package org.domainforge.web.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.shared.BusinessRuleViolation;
import org.domainforge.web.api.Problems;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.ServletRequestBindingException;

import java.io.Serial;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FailureDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(Problems.class);
    private final ListAppender<ILoggingEvent> capture = new ListAppender<>();
    private final List<ILoggingEvent> records = capture.list;
    private Level previousLevel;
    private boolean previousAdditive;

    private static PlatformExceptionAdvice advice() {
        return new PlatformExceptionAdvice();
    }

    // 只替代 Servlet 响应是否已提交的边界，真实 HTTP 与日志接收另由两消费者验证。
    private static HttpServletResponse response(boolean committed) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isCommitted")) {
                        return committed;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @BeforeEach
    void captureOnlyThisComponentsDiagnostics() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void restoreHostLogging() {
        logger.detachAppender(capture);
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void unknownFailureRetainsLocationAndCauseWithoutMessages() throws Exception {
        var failure = new IllegalStateException("synthetic-secret", new SensitiveFailure());
        failure.addSuppressed(new RuntimeException("suppressed-secret"));
        var result = advice().handle(failure, response(false));
        assertEquals(500, result.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", result.getBody().getProperties().get("code"));
        assertEquals("服务处理失败", result.getBody().getDetail());
        String diagnostic = onlyDiagnostic();
        assertTrue(diagnostic.contains("IllegalStateException"), diagnostic);
        assertTrue(diagnostic.contains("SensitiveFailure"), diagnostic);
        assertTrue(
                diagnostic.contains("unknownFailureRetainsLocationAndCauseWithoutMessages"), diagnostic);
    }

    @Test
    void expectedRejectionAndMalformedInputAreNotSystemFailures() throws Exception {
        var rejection = advice().handle(new Declined("synthetic-secret"), response(false));
        var invalid =
                advice().handle(new ServletRequestBindingException("synthetic-secret"), response(false));
        assertEquals(422, rejection.getStatusCode().value());
        assertEquals("DECLINED", rejection.getBody().getProperties().get("code"));
        assertEquals(400, invalid.getStatusCode().value());
        assertTrue(records.isEmpty());
    }

    @Test
    void committedResponseRethrowsWithoutDuplicateDiagnostics() {
        var failure = new IllegalStateException("synthetic-secret");
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> advice().handle(failure, response(true))));
        assertTrue(records.isEmpty());
    }

    @Test
    void cyclicCausesAndLargeStacksRemainBounded() throws Exception {
        var first = new IllegalStateException("synthetic-secret");
        var second = new IllegalArgumentException("cause-secret", first);
        first.initCause(second);
        var frames = new StackTraceElement[100];
        java.util.Arrays.fill(
                frames, new StackTraceElement("example.Operation", "execute", "Operation.java", 42));
        first.setStackTrace(frames);
        second.setStackTrace(frames);
        advice().handle(first, response(false));
        String diagnostic = onlyDiagnostic();
        assertTrue(diagnostic.contains("example.Operation.execute"), diagnostic);
        assertTrue(diagnostic.contains("truncated"), diagnostic);
        assertTrue(diagnostic.length() <= 4096);
    }

    private String onlyDiagnostic() {
        assertEquals(1, records.size(), "故障必须留下且只留一条内部诊断");
        ILoggingEvent record = records.getFirst();
        assertEquals(Level.ERROR, record.getLevel());
        assertNull(record.getThrowableProxy(), "原始异常会将 message/cause/suppressed 交给日志后端泄漏");
        assertNull(record.getArgumentArray());
        String text = record.getFormattedMessage();
        assertFalse(text.contains("secret"), text);
        return text;
    }

    static class SensitiveFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public String getMessage() {
            throw new AssertionError("诊断不应读取异常消息");
        }

        public String toString() {
            throw new AssertionError("诊断不应序列化原始异常");
        }
    }

    static class Declined extends BusinessRuleViolation {
        @Serial
        private static final long serialVersionUID = 1L;

        Declined(String message) {
            super(Kind.UNPROCESSABLE, "DECLINED", message);
        }
    }
}
