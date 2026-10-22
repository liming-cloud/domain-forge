package org.domainforge.access.backend.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import org.domainforge.access.backend.internal.execution.RequestCurrentSubject;
import org.domainforge.access.backend.internal.remote.BackendStpLogic;
import org.junit.jupiter.api.Test;

/** 原生可选登录查询对照；仅模拟请求上下文，不替代远端身份与存储验证。 */
class BackendStpLogicTest {
  // 匿名查询返回null/false，必需身份仍拒绝；无令牌不能调用外部事实源。
  @Test
  void anonymousOptionalLookupMatchesNativeContract() {
    StpLogic previous = StpUtil.getStpLogic();
    SaTokenContextMockUtil.setMockContext();
    try {
      var baseline = new StpLogic("native-contract");
      assertNull(baseline.getLoginIdDefaultNull());
      assertFalse(baseline.isLogin());
      assertFalse(baseline.hasPermission("iam:account:read"));
      assertFalse(baseline.hasRole("manager"));
      var subjects =
          new RequestCurrentSubject(
              token -> {
                throw new AssertionError("匿名查询不应调用事实源");
              });
      var adapter = new BackendStpLogic(subjects);
      assertNull(adapter.getLoginIdDefaultNull());
      assertFalse(adapter.isLogin());
      assertFalse(adapter.hasPermission("iam:account:read"));
      assertFalse(adapter.hasRole("manager"));
      assertThrows(NotLoginException.class, adapter::getLoginId);
    } finally {
      StpUtil.setStpLogic(previous);
      SaTokenContextMockUtil.clearContext();
    }
  }
}
