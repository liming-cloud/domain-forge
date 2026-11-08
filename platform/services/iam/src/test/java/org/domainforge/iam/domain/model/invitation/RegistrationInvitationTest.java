package org.domainforge.iam.domain.model.invitation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 邀请状态转换测试不代替数据库并发和回滚验证。
 */
class RegistrationInvitationTest {
    private final Instant expires = Instant.parse("2026-10-21T00:00:00Z");
    private final RegistrationInvitation invitation =
            new RegistrationInvitation(1L, "invited.user", "a".repeat(64), expires, 2L, null, false);

    // 有效邀请消费成功，再次消费与重发均拒绝，不能重置已有账号。
    @Test
    void consumesOnlyOnce() {
        long account = 3L;
        RegistrationInvitation consumed = invitation.consume(account, expires.minusSeconds(1));
        assertEquals(account, consumed.consumedBy());
        assertRule("INVITATION_UNUSABLE", () -> consumed.consume(account, expires.minusSeconds(1)));
        assertRule("INVITATION_UNUSABLE", consumed::invalidate);
    }

    // 精确到期点即失效，重发产生的旧邀请失效事实同样阻断消费。
    @Test
    void expiryAndReissueRejectConsumption() {
        assertRule("INVITATION_UNUSABLE", () -> invitation.consume(3L, expires));
        RegistrationInvitation invalid = invitation.invalidate();
        assertTrue(invalid.invalidated());
        assertRule("INVITATION_UNUSABLE", () -> invalid.consume(3L, expires.minusSeconds(1)));
    }

    // 身份与消费者 ID 必须为正数，消费终态与失效终态互斥。
    @Test
    void invalidIdentitiesAndContradictoryStatesReject() {
        assertRule("INVITATION_STATE_INVALID",
                () -> new RegistrationInvitation(0L, "invited.user", "a".repeat(64), expires, 2L, null, false));
        assertRule("INVITATION_STATE_INVALID",
                () -> new RegistrationInvitation(1L, "invited.user", "a".repeat(64), expires, 2L, 0L, false));
        assertRule("INVITATION_STATE_INVALID",
                () -> new RegistrationInvitation(1L, "invited.user", "a".repeat(64), expires, 2L, 3L, true));
    }
}
