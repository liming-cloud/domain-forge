package org.domainforge.iam.application.command.organization;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.account.Membership;
import org.domainforge.iam.domain.model.organization.OrganizationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织树管理：同级不重名、移动不成环、仍有引用时不能删除，根受保护。
 */
class ManageOrganizationTest {
    private static final long ROOT = OrganizationUnit.ROOT_ID;
    private final InMemoryIam iam = new InMemoryIam();
    private final ManageOrganization units = new ManageOrganization(
            iam.authorization(), iam.organizations, iam.identifiers, iam.audit);
    private final String admin = iam.actor(10, true, "iam:org:manage");

    @Test
    void createsUnderExistingParent() {
        CommandResult result = units.create(admin, ROOT, "销售部");

        assertEquals(new OrganizationUnit(result.targetId(), ROOT, "销售部", true, 1),
                iam.organizations.lock(result.targetId()));
        assertEquals(List.of("org.insert " + result.targetId(), "audit org.create"), iam.writes);
    }

    @Test
    void createRejectsMissingParentDuplicateNameOrNonSystemOperator() {
        iam.unit(2, ROOT, "销售部");
        String operator = iam.actor(20, false, "iam:org:manage");
        assertFailure(IamFailure.CONFLICT, () -> units.create(admin, 404, "研发部"));
        assertFailure(IamFailure.CONFLICT, () -> units.create(admin, ROOT, "销售部"));
        assertFailure(IamFailure.FORBIDDEN, () -> units.create(operator, ROOT, "研发部"));
        assertEquals(2, iam.organizations.rows.size());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void movesAndRejectsCycle() {
        iam.unit(2, ROOT, "销售部");
        iam.unit(3, 2, "华东");
        iam.unit(4, ROOT, "研发部");

        assertRule("ORG_MOVE_CYCLE", () -> units.move(admin, 2, 1, 3));
        assertRule("ORG_ROOT_MOVE", () -> units.move(admin, ROOT, 1, 2));
        assertFailure(IamFailure.CONFLICT, () -> units.move(admin, 3, 1, 404));
        assertTrue(iam.writes.isEmpty());

        assertEquals(new CommandResult(3, 2), units.move(admin, 3, 1, 4));
        assertEquals(4L, iam.organizations.lock(3).parentId());
    }

    @Test
    void renameAndStateWithRootProtected() {
        iam.unit(2, ROOT, "销售部");
        assertEquals(new CommandResult(2, 2), units.rename(admin, 2, 1, "市场部"));
        assertEquals(new CommandResult(2, 3), units.state(admin, 2, 2, false));
        assertFalse(iam.organizations.lock(2).enabled());
        iam.writes.clear();
        assertRule("ORG_ROOT_DISABLE", () -> units.state(admin, ROOT, 1, false));
        assertFailure(IamFailure.CONFLICT, () -> units.rename(admin, 2, 1, "旧版本"));
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void deletesOnlyUnreferencedUnit() {
        iam.unit(2, ROOT, "销售部");
        iam.accounts.rows.put(20L, new Account(20, "user", "hash:secret", 1, 1, true, Set.of(),
                Set.of(new Membership(2, true))));
        assertFailure(IamFailure.CONFLICT, () -> units.delete(admin, 2, 1));
        assertRule("ORG_ROOT_DELETE", () -> units.delete(admin, ROOT, 1));
        assertTrue(iam.organizations.rows.containsKey(2L));
        assertTrue(iam.writes.isEmpty());

        iam.accounts.rows.clear();
        units.delete(admin, 2, 1);
        assertFalse(iam.organizations.rows.containsKey(2L));
        assertEquals(List.of("org.delete 2", "audit org.delete"), iam.writes);
    }
}
