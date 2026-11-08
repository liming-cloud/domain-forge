package org.domainforge.web.internal;

import org.domainforge.shared.BusinessRuleViolation;
import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.domainforge.shared.DependencyUnavailableException;
import org.domainforge.web.api.FailureTranslator;
import org.domainforge.web.api.Problems;
import org.domainforge.web.autoconfigure.WebSupportAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProblemContractTest {
    private static final Map<Kind, Integer> STATUS =
            Map.of(
                    Kind.INVALID_REQUEST, 400,
                    Kind.UNAUTHENTICATED, 401,
                    Kind.FORBIDDEN, 403,
                    Kind.NOT_FOUND, 404,
                    Kind.CONFLICT, 409,
                    Kind.PAYLOAD_TOO_LARGE, 413,
                    Kind.UNPROCESSABLE, 422,
                    Kind.RATE_LIMITED, 429);

    @Test
    void everyViolationKindHasItsStatusAndCarriesTheCodeInProblemDetails() {
        assertEquals(Kind.values().length, STATUS.size(), "新增类别必须同步状态表");
        STATUS.forEach((kind, status) -> {
            var result = Problems.dispatch(new Violation(kind, "SOME_RULE", "规则说明"));
            assertEquals(status, result.getStatusCode().value());
            assertEquals(status, result.getBody().getStatus());
            assertEquals("SOME_RULE", result.getBody().getProperties().get("code"));
            assertEquals("规则说明", result.getBody().getDetail());
        });
    }

    @Test
    void unavailableDependencyIs503WithoutLeakingItsMessage() {
        var result = Problems.dispatch(new Unavailable("redis://secret-host"));
        assertEquals(503, result.getStatusCode().value());
        assertEquals("SERVICE_UNAVAILABLE", result.getBody().getProperties().get("code"));
        assertFalse(result.getBody().getDetail().contains("secret"));
    }

    @Test
    void programErrorIs500AndMalformedInputIs400() {
        var error = Problems.dispatch(new IllegalStateException("boom"));
        assertEquals(500, error.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", error.getBody().getProperties().get("code"));
        var malformed = Problems
                .dispatch(new HttpMessageNotReadableException("x", (HttpInputMessage) null));
        assertEquals(400, malformed.getStatusCode().value());
        assertEquals(400, Problems.dispatch(new MultipartException("x")).getStatusCode().value());
        var tooLarge = Problems.dispatch(new MaxUploadSizeExceededException(1));
        assertEquals(413, tooLarge.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", tooLarge.getBody().getProperties().get("code"));
    }

    @Test
    void registeredTranslatorsBringForeignExceptionsIntoTheDispatchPoint() {
        var result = Problems.dispatch(new ForeignFailure());
        assertEquals(409, result.getStatusCode().value());
        assertEquals("FOREIGN", result.getBody().getProperties().get("code"));
    }

    @Test
    void filterBodyIsTheSameProblemShape() {
        assertEquals(
                "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                        + "\"detail\":\"需要\\\"身份\\\"认证\",\"code\":\"UNAUTHENTICATED\"}",
                Problems.json(401, "UNAUTHENTICATED", "需要\"身份\"认证"));
    }

    @Test
    void violationRejectsMalformedCodeAndBlankMessage() {
        assertThrows(IllegalArgumentException.class, () -> new Violation(Kind.CONFLICT, "lower", "说明"));
        assertThrows(IllegalArgumentException.class, () -> new Violation(Kind.CONFLICT, "OK", " "));
        assertThrows(IllegalArgumentException.class, () -> new Violation(null, "OK", "说明"));
    }

    @Test
    void nonServletContextDoesNotActivateWebSupport() {
        try (var context = new AnnotationConfigApplicationContext(WebSupportAutoConfiguration.class)) {
            assertTrue(context.getBeansOfType(PlatformExceptionAdvice.class).isEmpty());
        }
    }

    static final class Violation extends BusinessRuleViolation {
        Violation(Kind kind, String code, String message) {
            super(kind, code, message);
        }
    }

    static final class Unavailable extends DependencyUnavailableException {
        Unavailable(String message) {
            super(message, null);
        }
    }

    static final class ForeignFailure extends RuntimeException {
    }

    public static final class ForeignTranslator implements FailureTranslator {
        @Override
        public RuntimeException translate(Exception failure) {
            return failure instanceof ForeignFailure ? new Violation(Kind.CONFLICT, "FOREIGN", "第三方失败") : null;
        }
    }
}
