package org.domainforge.iam.domain.model.account;

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
        assertEquals(Set.of(), account.roleIds());
        assertEquals(Set.of(), account.memberships());
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
    void rolesAreBoundedAndApplyWithoutPasswordChange() {
        Account account = Account.register(1L, "reader.a", "hash");
        Account assigned = account.changeRoles(Set.of(7L));
        assertEquals(account.credentialVersion(), assigned.credentialVersion());
        assertTrue(assigned.version() > account.version());
        assertSame(assigned, assigned.changeRoles(Set.of(7L)));
        assertThrows(UnsupportedOperationException.class, () -> assigned.roleIds().clear());
        var tooMany = new java.util.HashSet<Long>();
        for (long id = 1; id <= 21; id++) tooMany.add(id);
        assertThrows(IllegalArgumentException.class, () -> account.changeRoles(tooMany));
        assertThrows(IllegalArgumentException.class, () -> account.changeRoles(Set.of(0L)));
    }

    @Test
    void systemAdministratorRequiresEnabledAccountWithSystemRole() {
        Account admin = Account.administrator(1L, "admin.a", "hash");
        assertTrue(admin.isSystemAdministrator());
        assertFalse(admin.changeEnabled(false).isSystemAdministrator());
        assertFalse(admin.changeRoles(Set.of()).isSystemAdministrator());
        assertFalse(Account.register(2L, "reader.a", "hash").isSystemAdministrator());
    }

    @Test
    void membershipsHaveExactlyOnePrimaryWhenPresent() {
        Account account = Account.register(1L, "reader.a", "hash");
        Account member = account.changeMemberships(Set.of(new Membership(10L, true), new Membership(11L, false)));
        assertEquals(2, member.memberships().size());
        assertEquals(Set.of(), member.changeMemberships(Set.of()).memberships());
        assertThrows(IllegalArgumentException.class,
                () -> account.changeMemberships(Set.of(new Membership(10L, false))));
        assertThrows(IllegalArgumentException.class, () -> account.changeMemberships(
                Set.of(new Membership(10L, true), new Membership(11L, true))));
        assertThrows(IllegalArgumentException.class, () -> account.changeMemberships(
                Set.of(new Membership(10L, true), new Membership(10L, false))));
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
