package org.domainforge.iam.application.command.authentication;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.List;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录：令牌只在 SQL 许可登记后交付，失败不留下许可或原生会话。
 */
class LoginTest {
    private static final long USER = 20;
    private final InMemoryIam iam = new InMemoryIam();
    private final Login login = new Login(iam.accounts, iam.passwords, iam.protection, iam.nativeSessions,
            iam.sessions, iam.secrets, iam.transactions, iam.clock);

    LoginTest() {
        iam.account(USER, "user");
    }

    @Test
    void grantsEightHourSessionForCurrentCredential() {
        LoginResult result = login.execute("User", "secret", "127.0.0.1");

        assertEquals(Duration.ofHours(8).toSeconds(), result.expiresInSeconds());
        SessionGrant grant = iam.sessions.grants.getFirst();
        assertEquals(new SessionGrant(iam.secrets.digest(result.token()), USER, 1, InMemoryIam.NOW.plus(
                Duration.ofHours(8))), grant);
        assertEquals(USER, iam.nativeSessions.subject(result.token()));
        assertTrue(iam.protection.pending.isEmpty(), "成功只移除本次预占");
        assertEquals(List.of("session.insert 20"), iam.writes);
    }

    @Test
    void wrongPasswordUnknownOrDisabledAccountAreTheSameFailure() {
        iam.account(USER + 1, "disabled");
        iam.accounts.rows.put(USER + 1, iam.accounts.find(USER + 1).changeEnabled(false));

        assertFailure(Kind.UNAUTHENTICATED, () -> login.execute("user", "wrong", "127.0.0.1"));
        assertFailure(Kind.UNAUTHENTICATED, () -> login.execute("nobody", "secret", "127.0.0.1"));
        assertFailure(Kind.UNAUTHENTICATED, () -> login.execute("disabled", "secret", "127.0.0.1"));
        assertTrue(iam.writes.isEmpty());
        assertTrue(iam.nativeSessions.live.isEmpty());
        assertEquals(3, iam.protection.pending.size(), "失败保留预占计数");
    }

    @Test
    void rateLimitedAttemptDoesNotCheckPassword() {
        iam.protection.limited = true;
        assertFailure(Kind.RATE_LIMITED, () -> login.execute("user", "secret", "127.0.0.1"));
        assertTrue(iam.writes.isEmpty());
        assertTrue(iam.nativeSessions.live.isEmpty());
    }

    // 密码校验后、登记许可前账号被改密：本次登录失败并清理已签发的原生会话。
    @Test
    void credentialChangedDuringLoginCleansNativeSession() {
        TransactionOperations changedFirst = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                iam.accounts.rows.put(USER, iam.accounts.find(USER).changePassword("hash:other"));
                return iam.transactions.execute(action);
            }
        };
        Login racing = new Login(iam.accounts, iam.passwords, iam.protection, iam.nativeSessions,
                iam.sessions, iam.secrets, changedFirst, iam.clock);

        assertFailure(Kind.UNAUTHENTICATED, () -> racing.execute("user", "secret", "127.0.0.1"));
        assertTrue(iam.sessions.grants.isEmpty());
        assertTrue(iam.nativeSessions.live.isEmpty());
    }
}
