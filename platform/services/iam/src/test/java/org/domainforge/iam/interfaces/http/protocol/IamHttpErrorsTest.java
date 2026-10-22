package org.domainforge.iam.interfaces.http.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IamHttpErrorsTest {
  // 工作池暂时饱和不能向客户端谎报账号仍受限十分钟。
  @Test
  void hashCapacityUsesShortRetryAdvice() {
    var response =
        new IamHttpErrors()
            .handle(
                new IamException(IamFailure.RATE_LIMITED, "密码计算繁忙，请稍后重试"),
                new MockHttpServletRequest());
    assertEquals(429, response.getStatusCode().value());
    assertEquals("1", response.getHeaders().getFirst("Retry-After"));
  }
}
