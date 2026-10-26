package org.domainforge.iam.domain.model.role;

import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        Role system = new Role(Role.SYSTEM_ADMIN_ID, "SYSTEM_ADMIN", "系统管理员", true, true, 1, Set.of());
        assertThrows(IllegalStateException.class, () -> system.changeEnabled(false));
        assertThrows(IllegalStateException.class, () -> system.replaceGrants(Set.of()));
        assertThrows(IllegalStateException.class, system::requireDeletable);
        assertThrows(IllegalArgumentException.class, () -> Role.create(1L, "OTHER", "x"));
        assertThrows(IllegalArgumentException.class, () -> Role.create(5L, "system_admin", "x"));
    }

    @Test
    void ordinaryRoleLifecycle() {
        Role role = Role.create(5L, "SALES", " 销售 ");
        assertEquals("销售", role.name());
        assertSame(role, role.rename("销售"));
        assertThrows(IllegalStateException.class, role::requireDeletable);
        Role disabled = role.changeEnabled(false);
        assertFalse(disabled.enabled());
        assertDoesNotThrow(disabled::requireDeletable);
        assertThrows(IllegalArgumentException.class, () -> Role.create(6L, "1bad", "x"));
    }

    @Test
    void grantScopeMustMatchCatalogDeclaration() {
        var self = new PermissionGrant("sales:order:read", DataScope.SELF, Set.of());
        assertDoesNotThrow(() -> self.requireCompatible(SCOPED, false));
        var notApplicable = new PermissionGrant("sales:order:read", DataScope.NOT_APPLICABLE, Set.of());
        assertThrows(IllegalArgumentException.class, () -> notApplicable.requireCompatible(SCOPED, false));
        var scopedPlain = new PermissionGrant("sales:order:export", DataScope.ALL, Set.of());
        assertThrows(IllegalArgumentException.class, () -> scopedPlain.requireCompatible(PLAIN, false));
        var system = new PermissionGrant("iam:org:manage", DataScope.NOT_APPLICABLE, Set.of());
        assertThrows(IllegalArgumentException.class, () -> system.requireCompatible(SYSTEM, false));
        assertDoesNotThrow(() -> system.requireCompatible(SYSTEM, true));
        assertThrows(IllegalArgumentException.class, () -> self.requireCompatible(null, false));
    }

    @Test
    void customScopeListsUnitsOthersDoNot() {
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionGrant("sales:order:read", DataScope.CUSTOM_DEPARTMENTS, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionGrant("sales:order:read", DataScope.DEPARTMENT, Set.of(3L)));
        assertEquals(Set.of(3L),
                new PermissionGrant("sales:order:read", DataScope.CUSTOM_DEPARTMENTS, Set.of(3L)).unitIds());
    }

    @Test
    void permissionGrantedOnce() {
        var first = new PermissionGrant("sales:order:read", DataScope.SELF, Set.of());
        var second = new PermissionGrant("sales:order:read", DataScope.ALL, Set.of());
        Role role = Role.create(5L, "SALES", "销售");
        assertThrows(IllegalArgumentException.class, () -> role.replaceGrants(Set.of(first, second)));
        Role granted = role.replaceGrants(Set.of(first));
        assertEquals(2, granted.version());
        assertSame(granted, granted.replaceGrants(Set.of(first)));
    }
}
