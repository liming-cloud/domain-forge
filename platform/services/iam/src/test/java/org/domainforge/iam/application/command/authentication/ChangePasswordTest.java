package org.domainforge.iam.application.command.authentication;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自助改密：需要当前会话与旧密码，成功后凭据版本提升。
 */
class ChangePasswordTest {
    private static final long USER = 20;
    private final InMemoryIam iam = new InMemoryIam();
    private final ChangePassword change = new ChangePassword(
            iam.transactions, iam.authorization(), iam.accounts, iam.passwords, iam.audit);
    private final String digest = iam.actor(USER, false);
    private final CurrentIdentity identity = iam.sessions.current(digest);

    ChangePasswordTest() {
        iam.account(USER, "user");
    }

    @Test
    void savesNewDigestAndRaisesCredentialVersion() {
        assertEquals(new CommandResult(USER, 2), change.execute(identity, "secret", "next"));

        var saved = iam.accounts.find(USER);
        assertEquals("hash:next", saved.credentialDigest());
        assertEquals(2, saved.credentialVersion());
        assertEquals(List.of("account.update 20", "audit account.password"), iam.writes);
    }

    @Test
    void wrongOldPasswordOrEndedSessionIsRejected() {
        assertFailure(IamFailure.UNAUTHENTICATED, () -> change.execute(identity, "wrong", "next"));
        iam.sessions.identities.remove(digest);
        assertFailure(IamFailure.UNAUTHENTICATED, () -> change.execute(identity, "secret", "next"));
        assertEquals("hash:secret", iam.accounts.find(USER).credentialDigest());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void disabledAccountCannotChangePassword() {
        iam.accounts.rows.put(USER, iam.accounts.find(USER).changeEnabled(false));
        assertRule("ACCOUNT_DISABLED", () -> change.execute(identity, "secret", "next"));
        assertTrue(iam.writes.isEmpty());
    }
}
