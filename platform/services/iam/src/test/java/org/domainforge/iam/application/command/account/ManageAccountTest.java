package org.domainforge.iam.application.command.account;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.account.Membership;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号管理命令：状态、角色分配、成员归属，以及“至少保留一名系统管理员”。
 */
class ManageAccountTest {
    private static final long ADMIN = 10;
    private static final long USER = 20;
    private static final long SALES = 30;
    private final InMemoryIam iam = new InMemoryIam();
    private final ManageAccount accounts = new ManageAccount(
            iam.guard, iam.authorization(), iam.accounts, iam.roles, iam.organizations, iam.audit);
    private final String admin = iam.actor(ADMIN, true, "iam:account:state", "iam:account:role", "iam:member:manage");

    ManageAccountTest() {
        iam.role(SystemRole.ADMIN_ID, "SYSTEM_ADMIN", true);
        iam.account(ADMIN, "admin", SystemRole.ADMIN_ID);
        iam.account(USER, "user");
    }

    @Test
    void disablingRaisesCredentialVersionAndAudits() {
        CommandResult result = accounts.state(admin, USER, 1, false);

        Account saved = iam.accounts.find(USER);
        assertEquals(new CommandResult(USER, 2), result);
        assertFalse(saved.enabled());
        assertEquals(2, saved.credentialVersion());
        assertEquals(List.of("account.update 20", "audit account.state"), iam.writes);
        assertEquals("10", iam.audits.getFirst().actor());
    }

    @Test
    void unchangedStateWritesNothing() {
        assertEquals(new CommandResult(USER, 1), accounts.state(admin, USER, 1, true));
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void stateRequiresPermission() {
        String clerk = iam.actor(USER, false);
        assertFailure(IamFailure.FORBIDDEN, () -> accounts.state(clerk, ADMIN, 1, false));
        assertFailure(IamFailure.UNAUTHENTICATED, () -> accounts.state("unknown", USER, 1, false));
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void onlySystemAdministratorMayChangeSystemAdministrator() {
        String operator = iam.actor(USER, false, "iam:account:state");
        assertFailure(IamFailure.FORBIDDEN, () -> accounts.state(operator, ADMIN, 1, false));
        assertTrue(iam.accounts.find(ADMIN).enabled());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void staleVersionAndInvalidTargetAreRejected() {
        assertFailure(IamFailure.CONFLICT, () -> accounts.state(admin, USER, 2, false));
        assertFailure(IamFailure.CONFLICT, () -> accounts.state(admin, 999, 1, false));
        assertFailure(IamFailure.INVALID_REQUEST, () -> accounts.state(admin, USER, 0, false));
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void lastSystemAdministratorIsKept() {
        assertFailure(IamFailure.CONFLICT, () -> accounts.state(admin, ADMIN, 1, false));
        assertFailure(IamFailure.CONFLICT, () -> accounts.roles(admin, ADMIN, 1, Set.of()));
        assertTrue(iam.accounts.find(ADMIN).isSystemAdministrator());
        assertTrue(iam.writes.isEmpty());

        iam.account(USER + 1, "second", SystemRole.ADMIN_ID);
        assertEquals(new CommandResult(ADMIN, 2), accounts.state(admin, ADMIN, 1, false));
    }

    @Test
    void assignsEnabledRoles() {
        iam.role(SALES, "SALES", true);
        assertEquals(new CommandResult(USER, 2), accounts.roles(admin, USER, 1, Set.of(SALES)));
        assertEquals(Set.of(SALES), iam.accounts.find(USER).roleIds());
        assertEquals(List.of("account.update 20", "audit account.roles"), iam.writes);
    }

    @Test
    void roleAssignmentRejectsMissingDisabledOrTooManyRoles() {
        iam.role(SALES, "SALES", false);
        assertFailure(IamFailure.CONFLICT, () -> accounts.roles(admin, USER, 1, Set.of(SALES)));
        assertFailure(IamFailure.CONFLICT, () -> accounts.roles(admin, USER, 1, Set.of(404L)));
        Set<Long> tooMany = new HashSet<>();
        for (long id = 1; id <= Account.MAX_ROLES + 1; id++) tooMany.add(id);
        assertFailure(IamFailure.INVALID_REQUEST, () -> accounts.roles(admin, USER, 1, tooMany));
        assertFailure(IamFailure.INVALID_REQUEST, () -> accounts.roles(admin, USER, 1, null));
        assertTrue(iam.accounts.find(USER).roleIds().isEmpty());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void roleAssignmentRequiresSystemAdministrator() {
        iam.role(SALES, "SALES", true);
        String operator = iam.actor(USER + 1, false, "iam:account:role");
        assertFailure(IamFailure.FORBIDDEN, () -> accounts.roles(operator, USER, 1, Set.of(SALES)));
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void replacesMemberships() {
        iam.unit(2, 1, "销售部");
        var result = accounts.memberships(admin, USER, 1, List.of(new MembershipInput(2, true)));
        assertEquals(new CommandResult(USER, 2), result);
        assertEquals(Set.of(new Membership(2, true)), iam.accounts.find(USER).memberships());
        assertEquals(List.of("account.update 20", "audit account.memberships"), iam.writes);
    }

    @Test
    void membershipsRejectMissingUnitOrPrimaryRule() {
        iam.unit(2, 1, "销售部");
        assertFailure(IamFailure.CONFLICT,
                () -> accounts.memberships(admin, USER, 1, List.of(new MembershipInput(404, true))));
        assertRule("MEMBERSHIP_PRIMARY_INVALID",
                () -> accounts.memberships(admin, USER, 1, List.of(new MembershipInput(2, false))));
        assertFailure(IamFailure.INVALID_REQUEST, () -> accounts.memberships(admin, USER, 1, null));
        assertTrue(iam.accounts.find(USER).memberships().isEmpty());
        assertTrue(iam.writes.isEmpty());
    }

    // 现状：重复归属抛 IllegalArgumentException，经协议层会成为 500；修复见 #138，本测试只锁定现状。
    @Test
    void duplicateMembershipCurrentlyEscapesAsIllegalArgument() {
        iam.unit(2, 1, "销售部");
        var duplicate = List.of(new MembershipInput(2, true), new MembershipInput(2, true));
        assertThrows(IllegalArgumentException.class, () -> accounts.memberships(admin, USER, 1, duplicate));
        assertTrue(iam.writes.isEmpty());
    }
}
