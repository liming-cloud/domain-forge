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
            new RegistrationInvitation(1L, "invited.user", "a".repeat(64), expires, 2L, false);

    // 到期点之前可用；精确到期点即失效。
    @Test
    void usableOnlyBeforeExpiry() {
        invitation.requireUsable(expires.minusSeconds(1));
        assertRule("INVITATION_UNUSABLE", () -> invitation.requireUsable(expires));
    }

    // 重发使旧邀请失效，失效后不可用也不可重复失效。
    @Test
    void reissueInvalidatesOnce() {
        RegistrationInvitation invalid = invitation.invalidate();
        assertTrue(invalid.invalidated());
        assertRule("INVITATION_UNUSABLE", () -> invalid.requireUsable(expires.minusSeconds(1)));
        assertRule("INVITATION_UNUSABLE", invalid::invalidate);
    }

    // 身份必须为正数，摘要必须是 64 位十六进制。
    @Test
    void invalidIdentitiesReject() {
        assertRule("INVITATION_STATE_INVALID",
                () -> new RegistrationInvitation(0L, "invited.user", "a".repeat(64), expires, 2L, false));
        assertRule("INVITATION_STATE_INVALID",
                () -> new RegistrationInvitation(1L, "invited.user", "bad", expires, 2L, false));
    }
}
