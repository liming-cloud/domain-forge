package org.domainforge.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 合同边界验证可变集合和无效权限不能扩大已验证身份。 */
class CurrentSubjectTest {
  // 调用方后续修改原始集合不改变当前主体，返回集合也不可改写。
  @Test
  void identityIsImmutableAndBounded() {
    Set<String> input = new HashSet<>(Set.of("iam:account:read"));
    Set<String> roles = new HashSet<>(Set.of("manager"));
    var subject = new CurrentSubject("project-alpha", UUID.randomUUID(), input, roles);
    input.add("iam:grant:manage");
    roles.add("administrator");
    assertEquals(Set.of("manager"), subject.roles());
    assertThrows(UnsupportedOperationException.class, () -> subject.roles().clear());
    assertEquals(
        Set.of(), new CurrentSubject("project-alpha", UUID.randomUUID(), Set.of()).roles());
    assertEquals(1, subject.permissions().size());
    assertThrows(UnsupportedOperationException.class, () -> subject.permissions().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentSubject("bad project", UUID.randomUUID(), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentSubject("project-alpha", UUID.randomUUID(), Set.of("*")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentSubject("project-alpha", UUID.randomUUID(), Set.of(), Set.of("*")));
  }
}
