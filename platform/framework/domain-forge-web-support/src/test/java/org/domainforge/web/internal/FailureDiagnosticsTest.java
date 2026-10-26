package org.domainforge.web.internal;

import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.ServletRequestBindingException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class FailureDiagnosticsTest {
    private final Logger logger = Logger.getLogger(PlatformExceptionAdvice.class.getName());
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler capture =
            new Handler() {
                public void publish(LogRecord record) {
                    records.add(record);
                }

                public void flush() {
                }

                public void close() {
                }
            };
    private Level previousLevel;
    private boolean previousParentHandlers;

    private static PlatformExceptionAdvice advice(BusinessRejectionMapping<?>... mappings) {
        return new PlatformExceptionAdvice(new RejectionMappings(List.of(mappings)));
    }

    // 只替代 Servlet 响应是否已提交的边界，真实 HTTP 与日志接收另由两消费者验证。
    private static HttpServletResponse response(boolean committed) {
        return (HttpServletResponse)
                Proxy.newProxyInstance(
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
        previousParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
    }

    @AfterEach
    void restoreHostLogging() {
        logger.removeHandler(capture);
        logger.setLevel(previousLevel);
        logger.setUseParentHandlers(previousParentHandlers);
    }

    @Test
    void unknownFailureRetainsLocationAndCauseWithoutMessages() throws Exception {
        var failure = new IllegalStateException("synthetic-secret", new SensitiveFailure());
        failure.addSuppressed(new RuntimeException("suppressed-secret"));
        var result = advice().handle(failure, response(false));
        assertEquals(500, result.getStatusCode().value());
        assertEquals(new ApiResponse<>("INTERNAL_ERROR", "服务处理失败", null), result.getBody());
        String diagnostic = onlyDiagnostic();
        assertTrue(diagnostic.contains("IllegalStateException"), diagnostic);
        assertTrue(diagnostic.contains("SensitiveFailure"), diagnostic);
        assertTrue(
                diagnostic.contains("unknownFailureRetainsLocationAndCauseWithoutMessages"), diagnostic);
    }

    @Test
    void throwingMappingRetainsItsFailureLocation() throws Exception {
        var result =
                advice(new BrokenMapping()).handle(new Declined("synthetic-secret"), response(false));
        assertEquals(500, result.getStatusCode().value());
        String diagnostic = onlyDiagnostic();
        assertTrue(diagnostic.contains("UnsupportedOperationException"), diagnostic);
        assertTrue(diagnostic.contains("BrokenMapping.map"), diagnostic);
        assertTrue(diagnostic.contains("Declined"), diagnostic);
    }

    @Test
    void invalidMappingRetainsValidationFailure() throws Exception {
        var result =
                advice(new NullMapping()).handle(new Declined("synthetic-secret"), response(false));
        assertEquals(500, result.getStatusCode().value());
        String diagnostic = onlyDiagnostic();
        assertTrue(diagnostic.contains("IllegalArgumentException"), diagnostic);
        assertTrue(diagnostic.contains("RejectionMappings.apply"), diagnostic);
    }

    @Test
    void expectedRejectionAndMalformedInputAreNotSystemFailures() throws Exception {
        var rejection =
                advice(new SafeMapping()).handle(new Declined("synthetic-secret"), response(false));
        var invalid =
                advice().handle(new ServletRequestBindingException("synthetic-secret"), response(false));
        assertEquals(200, rejection.getStatusCode().value());
        assertEquals("DECLINED", rejection.getBody().code());
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
        LogRecord record = records.getFirst();
        assertEquals(Level.SEVERE, record.getLevel());
        assertNull(record.getThrown(), "原始异常会将 message/cause/suppressed 交给日志后端泄漏");
        assertNull(record.getParameters());
        String text = record.getMessage();
        assertFalse(text.contains("secret"), text);
        return text;
    }

    static class SensitiveFailure extends RuntimeException {
        public String getMessage() {
            throw new AssertionError("诊断不应读取异常消息");
        }

        public String toString() {
            throw new AssertionError("诊断不应序列化原始异常");
        }
    }

    static class Declined extends RuntimeException {
        Declined(String message) {
            super(message);
        }
    }

    static class SafeMapping implements BusinessRejectionMapping<Declined> {
        public Class<Declined> exceptionType() {
            return Declined.class;
        }

        public ApiResponse<Void> map(Declined exception) {
            return ApiResponse.rejected("DECLINED", "明确拒绝");
        }
    }

    static class BrokenMapping extends SafeMapping {
        public ApiResponse<Void> map(Declined exception) {
            throw new UnsupportedOperationException("mapping-secret");
        }
    }

    static class NullMapping extends SafeMapping {
        public ApiResponse<Void> map(Declined exception) {
            return null;
        }
    }
}
