package org.domainforge.access.backend.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.exception.NotLoginException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.backend.internal.execution.RequestCurrentSubject;
import org.domainforge.shared.CurrentSubject;
import org.junit.jupiter.api.Test;

/** 本请求同令牌快照的边界反例，不替代IAM真实SQL许可与Redis验证。 */
class RequestCurrentSubjectTest {
  // 切换凭证即废弃旧快照，失败后切回原凭证也必须重新验证完整事实。
  @Test
  void changedTokenNeverReusesOldSuccessAfterFailure() {
    for (var failure : AccessFailure.values()) {
      var reads = new AtomicInteger();
      var subjects =
          new RequestCurrentSubject(
              token -> {
                reads.incrementAndGet();
                if (token.equals("other")) throw new AccessException(failure);
                return new CurrentSubject("project-alpha", UUID.randomUUID(), Set.of());
              });
      SaTokenContextMockUtil.setMockContext();
      try {
        var first = subjects.current("first");
        assertSame(first, subjects.current("first"));
        assertThrows(RuntimeException.class, () -> subjects.current("other"));
        assertNotSame(first, subjects.current("first"));
        assertEquals(3, reads.get());
      } finally {
        SaTokenContextMockUtil.clearContext();
      }
    }
  }

  // 原生异常保留正确分类和登录类型，消息及快照表示均不能携带令牌。
  @Test
  void invalidIdentityUsesSanitizedNativeClassification() {
    SaTokenContextMockUtil.setMockContext();
    try {
      var subjects =
          new RequestCurrentSubject(
              token -> {
                throw new AccessException(AccessFailure.UNAUTHENTICATED);
              });
      var error =
          assertThrows(NotLoginException.class, () -> subjects.current("synthetic-secret-token"));
      assertEquals("login", error.getLoginType());
      assertEquals(NotLoginException.INVALID_TOKEN, error.getType());
      assertFalse(error.toString().contains("synthetic-secret-token"));
    } finally {
      SaTokenContextMockUtil.clearContext();
    }
  }
}
