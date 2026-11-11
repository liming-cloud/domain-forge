package org.domainforge.iam.application.command.authentication;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退出：只撤销本次会话，先提交 SQL 撤销与审计再清理原生会话。
 */
class LogoutTest {
    private static final long USER = 20;
    private final InMemoryIam iam = new InMemoryIam();
    private final Logout logout = new Logout(
            iam.transactions, iam.authorization(), iam.secrets, iam.sessions, iam.nativeSessions, iam.audit);
    private final String token = iam.nativeSessions.issue(USER);
    private final CurrentIdentity identity = signedIn(token);

    private CurrentIdentity signedIn(String nativeToken) {
        String digest = iam.secrets.digest(nativeToken);
        var current = new CurrentIdentity(USER, digest, 1, Set.of(), Set.of());
        iam.sessions.identities.put(digest, current);
        return current;
    }

    @Test
    void revokesCurrentSessionOnly() {
        String other = iam.nativeSessions.issue(USER);
        CurrentIdentity otherSession = signedIn(other);

        logout.execute(identity, token);

        assertNull(iam.sessions.current(identity.tokenDigest()));
        assertNull(iam.nativeSessions.subject(token));
        assertEquals(otherSession, iam.sessions.current(otherSession.tokenDigest()));
        assertEquals(USER, iam.nativeSessions.subject(other));
        assertEquals(List.of("session.revoke", "audit session.logout"), iam.writes);
    }

    @Test
    void tokenOfAnotherSessionIsRejected() {
        String other = iam.nativeSessions.issue(USER);
        signedIn(other);

        assertFailure(IamFailure.INVALID_REQUEST, () -> logout.execute(identity, other));
        assertFailure(IamFailure.INVALID_REQUEST, () -> logout.execute(identity, null));
        assertEquals(identity, iam.sessions.current(identity.tokenDigest()));
        assertTrue(iam.nativeSessions.live.containsKey(other));
        assertTrue(iam.writes.isEmpty());
    }
}
