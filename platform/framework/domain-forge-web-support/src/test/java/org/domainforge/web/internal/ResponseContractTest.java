package org.domainforge.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.domainforge.web.api.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;
import org.domainforge.web.api.HttpFailureResponses;
import org.domainforge.web.autoconfigure.WebSupportAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ResponseContractTest {
  @Test
  void reservedCodesCannotBecomeBusinessRejections() {
    for (String code :
        List.of(
            "SUCCESS",
            "ACCEPTED",
            "INVALID_REQUEST",
            "UNAUTHENTICATED",
            "FORBIDDEN",
            "SERVICE_UNAVAILABLE",
            "INTERNAL_ERROR")) {
      assertThrows(IllegalArgumentException.class, () -> ApiResponse.rejected(code, "拒绝"));
    }
    assertNull(ApiResponse.success(null).data());
    assertEquals(202, ApiResponse.accepted(null).getStatusCode().value());
    assertEquals(401, HttpFailureResponses.unauthenticated().getStatusCode().value());
    assertEquals(403, HttpFailureResponses.forbidden().getStatusCode().value());
    assertEquals(503, HttpFailureResponses.unavailable().getStatusCode().value());
  }

  @Test
  void duplicateAndBroadDeclarationsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RejectionMappings(List.of(new Mapping(), new Mapping())));
    assertThrows(
        IllegalArgumentException.class, () -> new RejectionMappings(List.of(new BroadMapping())));
  }

  @Test
  void exactMatchingDoesNotInspectSubclassOrCause() {
    RejectionMappings mappings = new RejectionMappings(List.of(new Mapping()));
    assertEquals("DECLINED", mappings.resolve(new Declined()).code());
    assertNull(mappings.resolve(new ChildDeclined()));
    assertNull(mappings.resolve(new RuntimeException(new Declined())));
  }

  @Test
  void nonServletContextDoesNotActivateWebSupport() {
    try (var context = new AnnotationConfigApplicationContext(WebSupportAutoConfiguration.class)) {
      assertTrue(context.getBeansOfType(PlatformExceptionAdvice.class).isEmpty());
    }
  }

  static class Declined extends RuntimeException {}

  static class ChildDeclined extends Declined {}

  static class Mapping implements BusinessRejectionMapping<Declined> {
    public Class<Declined> exceptionType() {
      return Declined.class;
    }

    public ApiResponse<Void> map(Declined exception) {
      return ApiResponse.rejected("DECLINED", "明确拒绝");
    }
  }

  static class BroadMapping implements BusinessRejectionMapping<RuntimeException> {
    public Class<RuntimeException> exceptionType() {
      return RuntimeException.class;
    }

    public ApiResponse<Void> map(RuntimeException exception) {
      return ApiResponse.rejected("DECLINED", "拒绝");
    }
  }
}
