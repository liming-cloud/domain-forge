package org.domainforge.iam.domain.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 独立验证真实密码规则；算法、线程池与数据库仍由各自原生测试覆盖。 */
class PasswordPolicyTest {
  // 空白和Unicode按原文保留；调用者改集合不能使已阻断密码重新被接受。
  @Test
  void keepsOriginalCharactersAndOwnsBlockedValues() {
    String blocked = "a blocked password value";
    var values = new HashSet<>(Set.of(blocked));
    var policy = new PasswordPolicy(values);
    values.clear();
    assertThrows(IllegalArgumentException.class, () -> policy.requireNew(blocked));
    assertDoesNotThrow(() -> policy.requireInput(blocked));
    assertDoesNotThrow(() -> policy.requireNew("   合法的密码内容保留所有空白   "));
  }

  // 精确码点边界、未配对代理项和空值在高成本哈希之前拒绝。
  @Test
  void rejectsMalformedAndOutOfRangeInput() {
    var policy = new PasswordPolicy(Set.of());
    assertDoesNotThrow(() -> policy.requireNew("😀".repeat(128)));
    assertThrows(IllegalArgumentException.class, () -> policy.requireInput("x".repeat(14)));
    assertThrows(IllegalArgumentException.class, () -> policy.requireInput("😀".repeat(129)));
    assertThrows(
        IllegalArgumentException.class, () -> policy.requireInput("x".repeat(15) + '\uD800'));
    assertThrows(IllegalArgumentException.class, () -> policy.requireInput(null));
  }
}
