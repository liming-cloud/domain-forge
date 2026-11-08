package org.domainforge.iam.domain.model.role;

import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 角色、授予与范围组合规则。
 */
class RoleTest {
    private static final PermissionDefinition SCOPED =
            new PermissionDefinition("sales:order:read", "查订单", true, false);
    private static final PermissionDefinition PLAIN =
            new PermissionDefinition("sales:order:export", "导出", false, false);
    private static final PermissionDefinition SYSTEM =
            new PermissionDefinition("iam:org:manage", "管组织", false, true);

    @Test
    void systemRoleIsProtected() {
        Role system = new Role(SystemRole.ADMIN_ID, "SYSTEM_ADMIN", "系统管理员", true, true, 1, Set.of());
        assertRule("ROLE_SYSTEM_DISABLE", () -> system.changeEnabled(false));
        assertRule("ROLE_SYSTEM_GRANTS", () -> system.replaceGrants(Set.of()));
        assertRule("ROLE_DELETE_NOT_ALLOWED", system::requireDeletable);
        assertRule("ROLE_SYSTEM_CREATE", () -> Role.create(1L, "OTHER", "x"));
        assertRule("ROLE_SYSTEM_CREATE", () -> Role.create(5L, "system_admin", "x"));
    }

    @Test
    void ordinaryRoleLifecycle() {
        Role role = Role.create(5L, "SALES", " 销售 ");
        assertEquals("销售", role.name());
        assertSame(role, role.rename("销售"));
        assertRule("ROLE_DELETE_NOT_ALLOWED", role::requireDeletable);
        Role disabled = role.changeEnabled(false);
        assertFalse(disabled.enabled());
        assertDoesNotThrow(disabled::requireDeletable);
        assertRule("ROLE_CODE_INVALID", () -> Role.create(6L, "1bad", "x"));
    }

    @Test
    void grantScopeMustMatchCatalogDeclaration() {
        var self = new PermissionGrant("sales:order:read", DataScope.SELF, Set.of());
        assertDoesNotThrow(() -> self.requireCompatible(SCOPED, false));
        var notApplicable = new PermissionGrant("sales:order:read", DataScope.NOT_APPLICABLE, Set.of());
        assertRule("GRANT_SCOPE_MISMATCH", () -> notApplicable.requireCompatible(SCOPED, false));
        var scopedPlain = new PermissionGrant("sales:order:export", DataScope.ALL, Set.of());
        assertRule("GRANT_SCOPE_MISMATCH", () -> scopedPlain.requireCompatible(PLAIN, false));
        var system = new PermissionGrant("iam:org:manage", DataScope.NOT_APPLICABLE, Set.of());
        assertRule("GRANT_SYSTEM_ONLY", () -> system.requireCompatible(SYSTEM, false));
        assertDoesNotThrow(() -> system.requireCompatible(SYSTEM, true));
        assertRule("GRANT_PERMISSION_UNKNOWN", () -> self.requireCompatible(null, false));
    }

    @Test
    void customScopeListsUnitsOthersDoNot() {
        assertRule("GRANT_UNITS_INVALID",
                () -> new PermissionGrant("sales:order:read", DataScope.CUSTOM_DEPARTMENTS, Set.of()));
        assertRule("GRANT_UNITS_INVALID",
                () -> new PermissionGrant("sales:order:read", DataScope.DEPARTMENT, Set.of(3L)));
        assertEquals(Set.of(3L),
                new PermissionGrant("sales:order:read", DataScope.CUSTOM_DEPARTMENTS, Set.of(3L)).unitIds());
    }

    @Test
    void permissionGrantedOnce() {
        var first = new PermissionGrant("sales:order:read", DataScope.SELF, Set.of());
        var second = new PermissionGrant("sales:order:read", DataScope.ALL, Set.of());
        Role role = Role.create(5L, "SALES", "销售");
        assertRule("GRANT_DUPLICATE", () -> role.replaceGrants(Set.of(first, second)));
        Role granted = role.replaceGrants(Set.of(first));
        assertEquals(2, granted.version());
        assertSame(granted, granted.replaceGrants(Set.of(first)));
    }
}
