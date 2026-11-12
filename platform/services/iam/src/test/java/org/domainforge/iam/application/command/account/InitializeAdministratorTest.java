package org.domainforge.iam.application.command.account;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 初始管理员：只能创建一次，账号、初始化标记与审计一起写入。
 */
class InitializeAdministratorTest {
    private final InMemoryIam iam = new InMemoryIam();
    private final InitializeAdministrator initialize = new InitializeAdministrator(
            iam.transactions, iam.guard, iam.accounts, iam.identifiers, iam.passwords, iam.audit);

    @Test
    void createsOnlySystemAdministrator() {
        long id = initialize.execute("Initial.Admin", "secret");

        Account created = iam.accounts.find(id);
        assertEquals("initial.admin", created.loginName());
        assertEquals(Set.of(SystemRole.ADMIN_ID), created.roleIds());
        assertEquals("hash:secret", created.credentialDigest());
        assertTrue(iam.guard.initialized);
        assertEquals(List.of("account.insert " + id, "guard.initialized", "audit administrator.initialize"),
                iam.writes);
        assertEquals("deployment-command", iam.audits.getFirst().actor());
    }

    @Test
    void secondInitializationIsRejectedWithoutWrites() {
        iam.guard.initialized = true;
        assertFailure(Kind.CONFLICT, () -> initialize.execute("other.admin", "secret"));
        assertRule("LOGIN_NAME_INVALID", () -> initialize.execute("x", "secret"));
        assertTrue(iam.accounts.rows.isEmpty());
        assertTrue(iam.writes.isEmpty());
    }
}
