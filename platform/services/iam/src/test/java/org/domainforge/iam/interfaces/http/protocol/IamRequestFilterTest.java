package org.domainforge.iam.interfaces.http.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 请求实际字节边界与媒体类型无关，不依赖声明的Content-Length。 */
class IamRequestFilterTest {
  // Jackson接受vendor JSON，因此不能仅拦截application/json；未知类型也不能无限读取。
  @Test
  void boundsEveryMediaTypeBeforeController() throws Exception {
    for (String media :
        new String[] {"application/json", "application/vnd.test+json", "text/plain", ""}) {
      var request = new MockHttpServletRequest("POST", "/api/auth/login");
      request.setContentType(media);
      request.setContent(new byte[16385]);
      var response = new MockHttpServletResponse();
      var reached = new AtomicBoolean();
      new IamRequestFilter().doFilter(request, response, (input, output) -> reached.set(true));
      assertEquals(400, response.getStatus(), media);
      assertFalse(reached.get(), media);
    }
  }

  // 上限内数据保持原字节，不能通过过滤器破坏密码Unicode或截断请求。
  @Test
  void passesExactBoundWithoutChangingBytes() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.setContentType("application/vnd.test+json");
    request.setContent(new byte[16384]);
    var response = new MockHttpServletResponse();
    new IamRequestFilter()
        .doFilter(
            request,
            response,
            (input, output) -> assertEquals(16384, input.getInputStream().readAllBytes().length));
    assertEquals("no-store", response.getHeader("Cache-Control"));
  }
}
