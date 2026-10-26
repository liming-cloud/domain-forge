package org.domainforge.iam.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 以可观察状态验证账号不变量，不使用持久化或会话替身。
 */
class AccountTest {
    @Test
    void registrationHasNoImplicitGrantAndNormalizesOnlyLoginName() {
        Account account = Account.register(1L, "Reader.A", "opaque-hash");
        assertEquals("reader.a", account.loginName());
        assertTrue(account.enabled());
        assertEquals(Set.of(), account.permissions());
        assertEquals(1, account.credentialVersion());
    }

    @Test
    void passwordAndDisableInvalidatePreviousCredentialVersion() {
        Account account = Account.register(1L, "reader.a", "hash-before");
        Account changed = account.changePassword("hash-after");
        assertEquals(2, changed.credentialVersion());
        assertEquals("hash-after", changed.credentialDigest());
        Account disabled = changed.changeEnabled(false);
        assertEquals(3, disabled.credentialVersion());
        Account enabled = disabled.changeEnabled(true);
        assertTrue(enabled.enabled());
        assertTrue(enabled.credentialVersion() > changed.credentialVersion());
        assertEquals("hash-before", account.credentialDigest());
    }

    @Test
    void grantsAreFiniteImmutableAndCurrentWithoutPasswordChange() {
        Account account = Account.register(1L, "reader.a", "hash");
        Account granted = account.changePermissions(Set.of("iam:account:read"));
        assertEquals(account.credentialVersion(), granted.credentialVersion());
        assertTrue(granted.version() > account.version());
        assertThrows(UnsupportedOperationException.class, () -> granted.permissions().clear());
        assertThrows(IllegalArgumentException.class, () -> account.changePermissions(Set.of("*")));
    }

    @Test
    void invalidNamesAndEmptyCredentialsCannotBecomeAccounts() {
        for (String value : new String[]{"ab", " user", "用户账号", "a/b", "a".repeat(65)})
            assertThrows(
                    IllegalArgumentException.class, () -> Account.register(1L, value, "hash"));
        assertThrows(
                IllegalArgumentException.class, () -> Account.register(1L, "reader.a", ""));
    }

    @Test
    void identityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Account.register(0L, "reader.a", "hash"));
        assertThrows(IllegalArgumentException.class, () -> Account.register(-1L, "reader.a", "hash"));
    }

    @Test
    void diagnosticRepresentationNeverContainsCredentialDigest() {
        Account account = Account.register(1L, "reader.a", "private-credential-digest");
        assertFalse(account.toString().contains("private-credential-digest"));
    }
}
