package org.domainforge.iam.domain.model.permission;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 权限目录命名空间规则。
 */
class PermissionNamespaceTest {
    @Test
    void namespaceOwnsItsCodes() {
        var read = new PermissionDefinition("sales:order:read", "查订单", true, false);
        assertDoesNotThrow(() -> new PermissionNamespace("sales", Set.of(read)));
        assertRule("PERMISSION_NAMESPACE_MISMATCH", () -> new PermissionNamespace("billing", Set.of(read)));
        assertRule("NAMESPACE_RESERVED", () -> new PermissionNamespace("iam", Set.of()));
        assertRule("PERMISSION_SYSTEM_ONLY", () -> new PermissionNamespace("sales",
                Set.of(new PermissionDefinition("sales:order:admin", "管理", false, true))));
    }

    @Test
    void codesHaveNoWildcards() {
        for (String code : new String[]{"sales", "sales:*", "Sales:order", "sales:order:read:x:y", "s:order"})
            assertRule("PERMISSION_CODE_INVALID", () -> PermissionDefinition.requireCode(code));
        assertRule("PERMISSION_SCOPE_INVALID", () -> new PermissionDefinition("sales:a", "x", true, true));
    }
}
