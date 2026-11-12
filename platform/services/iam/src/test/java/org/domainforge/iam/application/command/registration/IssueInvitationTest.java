package org.domainforge.iam.application.command.registration;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.domain.model.invitation.RegistrationInvitation;
import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签发与重发邀请：只保存秘密摘要，已用或换绑登录名的邀请不能重发。
 */
class IssueInvitationTest {
    private static final long ADMIN = 10;
    private final InMemoryIam iam = new InMemoryIam();
    private final IssueInvitation issue = new IssueInvitation(iam.clock, iam.authorization(), iam.accounts,
            iam.invitations, iam.identifiers, iam.secrets, iam.audit);
    private final String admin = iam.actor(ADMIN, false, "iam:invitation:create");

    @Test
    void storesDigestValidFor24Hours() {
        InvitationIssued issued = issue.execute(admin, "New.User", null);

        RegistrationInvitation saved = iam.invitations.lock(issued.invitationId());
        assertEquals("new.user", saved.loginName());
        assertEquals(iam.secrets.digest(issued.secret()), saved.secretDigest());
        assertEquals(InMemoryIam.NOW.plus(Duration.ofHours(24)), saved.expiresAt());
        assertEquals(ADMIN, saved.createdBy());
        assertEquals(List.of("invitation.insert " + issued.invitationId(), "audit invitation.issue"), iam.writes);
    }

    @Test
    void reissueInvalidatesOriginal() {
        long original = issue.execute(admin, "new.user", null).invitationId();
        iam.writes.clear();

        long next = issue.execute(admin, "new.user", original).invitationId();

        assertTrue(iam.invitations.lock(original).invalidated());
        assertFalse(iam.invitations.lock(next).invalidated());
        assertEquals(List.of("invitation.invalidate " + original, "invitation.insert " + next,
                "audit invitation.issue"), iam.writes);
    }

    @Test
    void rejectsWithoutPermissionOrForExistingAccount() {
        iam.account(20, "taken");
        String clerk = iam.actor(30, false);
        assertFailure(Kind.FORBIDDEN, () -> issue.execute(clerk, "new.user", null));
        assertFailure(Kind.CONFLICT, () -> issue.execute(admin, "taken", null));
        assertTrue(iam.invitations.rows.isEmpty());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void usedOrRebindingInvitationCannotBeReissued() {
        InvitationIssued original = issue.execute(admin, "new.user", null);
        iam.accounts.invitationDigests.put(99L, iam.secrets.digest(original.secret()));
        iam.writes.clear();

        assertFailure(Kind.CONFLICT, () -> issue.execute(admin, "new.user", original.invitationId()));
        assertFailure(Kind.CONFLICT, () -> issue.execute(admin, "other.user", original.invitationId()));
        assertFailure(Kind.CONFLICT, () -> issue.execute(admin, "new.user", 404L));
        assertFalse(iam.invitations.lock(original.invitationId()).invalidated());
        assertEquals(1, iam.invitations.rows.size());
        assertTrue(iam.writes.isEmpty());
    }
}
