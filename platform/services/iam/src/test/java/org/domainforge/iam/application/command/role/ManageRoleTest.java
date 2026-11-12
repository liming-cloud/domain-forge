package org.domainforge.iam.application.command.role;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.domain.model.role.DataScope;
import org.domainforge.iam.domain.model.role.PermissionGrant;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.shared.BusinessRuleViolation.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.application.FailureAssertions.assertInvalidRequest;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色管理：创建、改名、启停、授予与删除，只有系统管理员可以执行。
 */
class ManageRoleTest {
    private static final long SALES = 30;
    private static final String READ = "sales:order:read";
    private final InMemoryIam iam = new InMemoryIam();
    private final ManageRole roles = new ManageRole(
            iam.authorization(), iam.roles, iam.catalog, iam.organizations, iam.identifiers, iam.audit);
    private final String admin = iam.actor(10, true, "iam:role:manage");

    ManageRoleTest() {
        iam.role(SystemRole.ADMIN_ID, "SYSTEM_ADMIN", true);
        iam.role(SALES, "SALES", true);
        iam.permission(READ, true, false);
    }

    @Test
    void createsEnabledRoleWithoutGrants() {
        CommandResult result = roles.create(admin, "Support", "客服");

        var created = iam.roles.lock(result.targetId());
        assertEquals("Support", created.code());
        assertTrue(created.enabled());
        assertTrue(created.grants().isEmpty());
        assertEquals(List.of("role.insert " + result.targetId(), "audit role.create"), iam.writes);
    }

    @Test
    void createRejectsTakenCodeOrNonSystemOperator() {
        String operator = iam.actor(20, false, "iam:role:manage");
        assertEquals("ROLE_CODE_TAKEN", assertFailure(Kind.CONFLICT, () -> roles.create(admin, "sales", "重复")).code());
        assertFailure(Kind.FORBIDDEN, () -> roles.create(operator, "Support", "客服"));
        assertEquals(2, iam.roles.rows.size());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void renameAndStateFollowVersion() {
        assertEquals(new CommandResult(SALES, 2), roles.rename(admin, SALES, 1, "销售"));
        assertFailure(Kind.CONFLICT, () -> roles.state(admin, SALES, 1, false));
        assertEquals(new CommandResult(SALES, 3), roles.state(admin, SALES, 2, false));
        assertFalse(iam.roles.lock(SALES).enabled());
        assertEquals(List.of("role.update 30", "audit role.update", "role.update 30", "audit role.state"),
                iam.writes);
    }

    @Test
    void replacesGrantsCheckedAgainstCatalogAndUnits() {
        iam.unit(2, 1, "销售部");
        var grant = new GrantInput(READ, "CUSTOM_DEPARTMENTS", Set.of(2L));

        assertEquals(new CommandResult(SALES, 2), roles.grants(admin, SALES, 1, List.of(grant)));
        assertEquals(Set.of(new PermissionGrant(READ, DataScope.CUSTOM_DEPARTMENTS, Set.of(2L))),
                iam.roles.lock(SALES).grants());
        assertEquals(List.of("role.update 30", "audit role.grants"), iam.writes);
    }

    @Test
    void grantsRejectUnknownPermissionMissingUnitOrSystemRole() {
        var unknown = new GrantInput("sales:order:delete", "ALL", Set.of());
        var missingUnit = new GrantInput(READ, "CUSTOM_DEPARTMENTS", Set.of(404L));
        var notApplicable = new GrantInput(READ, "NOT_APPLICABLE", Set.of());
        assertRule("GRANT_PERMISSION_UNKNOWN", () -> roles.grants(admin, SALES, 1, List.of(unknown)));
        assertEquals("GRANT_UNIT_UNKNOWN",
                assertFailure(Kind.UNPROCESSABLE, () -> roles.grants(admin, SALES, 1, List.of(missingUnit))).code());
        assertRule("GRANT_SCOPE_MISMATCH", () -> roles.grants(admin, SALES, 1, List.of(notApplicable)));
        assertRule("ROLE_SYSTEM_GRANTS", () -> roles.grants(admin, SystemRole.ADMIN_ID, 1, List.of()));
        assertFailure(Kind.INVALID_REQUEST, () -> roles.grants(admin, SALES, 1, null));
        assertTrue(iam.roles.lock(SALES).grants().isEmpty());
        assertTrue(iam.writes.isEmpty());
    }

    // 未知或缺失的范围名称是非法输入（400），不是 500。
    @Test
    void unknownOrMissingScopeIsInvalidRequest() {
        var unknownScope = new GrantInput(READ, "EVERYTHING", Set.of());
        var missingScope = new GrantInput(READ, null, Set.of());
        assertInvalidRequest(() -> roles.grants(admin, SALES, 1, List.of(unknownScope)));
        assertInvalidRequest(() -> roles.grants(admin, SALES, 1, List.of(missingScope)));
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void deletesOnlyDisabledUnassignedRole() {
        assertRule("ROLE_DELETE_NOT_ALLOWED", () -> roles.delete(admin, SALES, 1));
        roles.state(admin, SALES, 1, false);
        iam.account(20, "user", SALES);
        iam.writes.clear();
        assertFailure(Kind.CONFLICT, () -> roles.delete(admin, SALES, 2));
        assertTrue(iam.writes.isEmpty());

        iam.accounts.rows.remove(20L);
        roles.delete(admin, SALES, 2);
        assertFalse(iam.roles.rows.containsKey(SALES));
        assertEquals(List.of("role.delete 30", "audit role.delete"), iam.writes);
    }
}
