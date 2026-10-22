package org.domainforge.iam.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.junit.jupiter.api.Test;

/** 只测试有限协议适配；不将替身响应当真实发现或 mTLS 证据。 */
class CurrentStateClientTest {
  @Test
  void rejectsUnsafeTokenAndProject() {
    for (String project : new String[] {"", "a", "project/other"})
      assertThrows(
          IllegalArgumentException.class, () -> new IamCurrentSubjectSource(null, project));
    var source =
        new IamCurrentSubjectSource(
            () ->
                ignored -> {
                  throw new AssertionError("非法令牌不得发出远程请求");
                },
            "project-alpha");
    for (String token : new String[] {"", "a\r\nb", "x".repeat(4097)})
      assertEquals(
          AccessFailure.UNAUTHENTICATED,
          assertThrows(AccessException.class, () -> source.resolve(token)).failure());
  }

  @Test
  void rejectsMalformedSuccessProtocol() {
    for (String[] pair :
        new String[][] {
          {"application/json-unsupported", ControlledIdentityEndpoint.VALID},
          {
            "application/json",
            ControlledIdentityEndpoint.VALID.replace(
                "00000001-0001-0001-0001-000000000001", "1-1-1-1-1")
          }
        }) {
      var source =
          new IamCurrentSubjectSource(
              () ->
                  ignored ->
                      Response.builder()
                          .status(200)
                          .request(
                              Request.create(
                                  Request.HttpMethod.GET,
                                  "https://iam/internal/access/current",
                                  Map.of(),
                                  null,
                                  StandardCharsets.UTF_8,
                                  null))
                          .headers(Map.of("Content-Type", List.of(pair[0])))
                          .body(pair[1], StandardCharsets.UTF_8)
                          .build(),
              "project-alpha");
      assertEquals(
          AccessFailure.UNAVAILABLE,
          assertThrows(AccessException.class, () -> source.resolve("synthetic-token")).failure());
    }
  }
}
