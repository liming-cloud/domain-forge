package org.domainforge.iam.application.command.registration;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.invitation.RegistrationInvitation;
import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接受邀请：只建账号，同一邀请只能成功一次；失效、过期或登录名不符都拒绝。
 */
class AcceptInvitationTest {
    private static final String SECRET = "a".repeat(43);
    private final InMemoryIam iam = new InMemoryIam();
    private final AcceptInvitation accept = new AcceptInvitation(iam.transactions, iam.clock, iam.accounts,
            iam.invitations, iam.identifiers, iam.passwords, iam.secrets, iam.audit);

    private RegistrationInvitation invite(long seconds, boolean invalidated) {
        var invitation = new RegistrationInvitation(
                5, "new.user", iam.secrets.digest(SECRET), InMemoryIam.NOW.plusSeconds(seconds), 10, invalidated);
        iam.invitations.rows.put(invitation.id(), invitation);
        return invitation;
    }

    @Test
    void registersOrdinaryAccountAndRecordsInvitation() {
        invite(60, false);

        CommandResult result = accept.execute(SECRET, "New.User", "secret");

        Account created = iam.accounts.find(result.targetId());
        assertEquals("new.user", created.loginName());
        assertTrue(created.roleIds().isEmpty());
        assertEquals("hash:secret", created.credentialDigest());
        assertTrue(iam.accounts.invitationUsed(iam.secrets.digest(SECRET)));
        assertEquals(List.of("account.insert " + result.targetId(), "audit invitation.accept"), iam.writes);
        assertEquals("invitation-holder", iam.audits.getFirst().actor());
    }

    @Test
    void invitationIsUsableOnlyOnce() {
        invite(60, false);
        accept.execute(SECRET, "new.user", "secret");
        iam.writes.clear();

        assertRule("INVITATION_UNUSABLE", () -> accept.execute(SECRET, "new.user", "secret"));
        assertEquals(1, iam.accounts.rows.size());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void expiredInvalidatedMismatchedOrMalformedAreRejected() {
        invite(0, false);
        assertRule("INVITATION_UNUSABLE", () -> accept.execute(SECRET, "new.user", "secret"));
        invite(60, true);
        assertRule("INVITATION_UNUSABLE", () -> accept.execute(SECRET, "new.user", "secret"));
        invite(60, false);
        assertRule("INVITATION_UNUSABLE", () -> accept.execute(SECRET, "other.user", "secret"));
        assertRule("INVITATION_UNUSABLE", () -> accept.execute("b".repeat(43), "new.user", "secret"));
        assertFailure(Kind.INVALID_REQUEST, () -> accept.execute("short", "new.user", "secret"));
        assertTrue(iam.accounts.rows.isEmpty());
        assertTrue(iam.writes.isEmpty());
    }
}
