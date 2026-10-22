package org.domainforge.iam.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 以可观察状态验证账号不变量，不使用持久化或会话替身。 */
class AccountTest {
  @Test
  void registrationHasNoImplicitGrantAndNormalizesOnlyLoginName() {
    Account account = Account.register(UUID.randomUUID(), "Reader.A", "opaque-hash");
    assertEquals("reader.a", account.loginName());
    assertTrue(account.enabled());
    assertEquals(Set.of(), account.permissions());
    assertEquals(1, account.credentialVersion());
  }

  @Test
  void passwordAndDisableInvalidatePreviousCredentialVersion() {
    Account account = Account.register(UUID.randomUUID(), "reader.a", "hash-before");
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
    Account account = Account.register(UUID.randomUUID(), "reader.a", "hash");
    Account granted = account.changePermissions(Set.of("iam:account:read"));
    assertEquals(account.credentialVersion(), granted.credentialVersion());
    assertTrue(granted.version() > account.version());
    assertThrows(UnsupportedOperationException.class, () -> granted.permissions().clear());
    assertThrows(IllegalArgumentException.class, () -> account.changePermissions(Set.of("*")));
  }

  @Test
  void invalidNamesAndEmptyCredentialsCannotBecomeAccounts() {
    for (String value : new String[] {"ab", " user", "用户账号", "a/b", "a".repeat(65)})
      assertThrows(
          IllegalArgumentException.class, () -> Account.register(UUID.randomUUID(), value, "hash"));
    assertThrows(
        IllegalArgumentException.class, () -> Account.register(UUID.randomUUID(), "reader.a", ""));
  }

  @Test
  void diagnosticRepresentationNeverContainsCredentialDigest() {
    Account account = Account.register(UUID.randomUUID(), "reader.a", "private-credential-digest");
    assertFalse(account.toString().contains("private-credential-digest"));
  }
}
