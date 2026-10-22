package org.domainforge.iam.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 邀请状态转换测试不代替数据库并发和回滚验证。 */
class RegistrationInvitationTest {
  private final Instant expires = Instant.parse("2026-10-21T00:00:00Z");
  private final RegistrationInvitation invitation =
      new RegistrationInvitation(
          UUID.randomUUID(),
          "invited.user",
          "a".repeat(64),
          expires,
          UUID.randomUUID(),
          null,
          false);

  // 有效邀请消费成功，再次消费与重发均拒绝，不能重置已有账号。
  @Test
  void consumesOnlyOnce() {
    UUID account = UUID.randomUUID();
    RegistrationInvitation consumed = invitation.consume(account, expires.minusSeconds(1));
    assertEquals(account, consumed.consumedBy());
    assertThrows(
        IllegalStateException.class, () -> consumed.consume(account, expires.minusSeconds(1)));
    assertThrows(IllegalStateException.class, consumed::invalidate);
  }

  // 精确到期点即失效，重发产生的旧邀请失效事实同样阻断消费。
  @Test
  void expiryAndReissueRejectConsumption() {
    assertThrows(IllegalStateException.class, () -> invitation.consume(UUID.randomUUID(), expires));
    RegistrationInvitation invalid = invitation.invalidate();
    assertTrue(invalid.invalidated());
    assertThrows(
        IllegalStateException.class,
        () -> invalid.consume(UUID.randomUUID(), expires.minusSeconds(1)));
  }
}
