package org.domainforge.iam.domain.model.permission;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 权限目录命名空间规则。
 */
class PermissionNamespaceTest {
    @Test
    void namespaceOwnsItsCodes() {
        var read = new PermissionDefinition("sales:order:read", "查订单", true, false);
        assertDoesNotThrow(() -> new PermissionNamespace("sales", Set.of(read)));
        assertThrows(IllegalArgumentException.class, () -> new PermissionNamespace("billing", Set.of(read)));
        assertThrows(IllegalArgumentException.class, () -> new PermissionNamespace("iam", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PermissionNamespace("sales",
                Set.of(new PermissionDefinition("sales:order:admin", "管理", false, true))));
    }

    @Test
    void codesHaveNoWildcards() {
        for (String code : new String[]{"sales", "sales:*", "Sales:order", "sales:order:read:x:y", "s:order"})
            assertThrows(IllegalArgumentException.class, () -> PermissionDefinition.requireCode(code));
        assertThrows(IllegalArgumentException.class, () -> new PermissionDefinition("sales:a", "x", true, true));
    }
}
