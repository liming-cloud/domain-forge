package org.domainforge.iam.application.command.catalog;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.role.DataScope;
import org.domainforge.iam.domain.model.role.PermissionGrant;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.iam.domain.repository.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限目录安装：整体替换命名空间，被引用的权限不能移除或改变范围声明。
 */
class InstallPermissionsTest {
    private static final long ADMIN = 10;
    private static final PermissionInput READ = new PermissionInput("sales:order:read", "查询订单", true, false);
    private static final PermissionInput EXPORT = new PermissionInput("sales:order:export", "导出", false, false);
    private final InMemoryIam iam = new InMemoryIam();
    private final InstallPermissions install = new InstallPermissions(iam.authorization(), iam.catalog, iam.audit);
    private final String admin = iam.actor(ADMIN, true, "iam:catalog:manage");

    @Test
    void replacesNamespaceAndSkipsUnchangedContent() {
        assertEquals(2, install.install(admin, "sales", List.of(READ, EXPORT)));
        assertEquals(Set.of("sales:order:read", "sales:order:export"), iam.catalog.rows.keySet());
        assertEquals(List.of("catalog.replace sales", "audit catalog.install"), iam.writes);

        iam.writes.clear();
        assertEquals(2, install.install(admin, "sales", List.of(EXPORT, READ)));
        assertTrue(iam.writes.isEmpty(), "内容不变时不写入也不审计");
    }

    @Test
    void referencedPermissionCannotBeRemovedOrRescoped() {
        install.install(admin, "sales", List.of(READ, EXPORT));
        iam.roles.rows.put(5L, new Role(5, "SALES", "销售", true, false, 1,
                Set.of(new PermissionGrant("sales:order:read", DataScope.ALL, Set.of()))));
        iam.writes.clear();

        assertFailure(IamFailure.CONFLICT, () -> install.install(admin, "sales", List.of(EXPORT)));
        var unscoped = new PermissionInput("sales:order:read", "查询订单", false, false);
        assertFailure(IamFailure.CONFLICT, () -> install.install(admin, "sales", List.of(unscoped, EXPORT)));
        assertEquals(2, iam.catalog.rows.size());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void onlySystemAdministratorMayInstall() {
        String operator = iam.actor(20, false, "iam:catalog:manage");
        assertFailure(IamFailure.FORBIDDEN, () -> install.install(operator, "sales", List.of(READ)));
        assertRule("NAMESPACE_RESERVED", () -> install.install(admin, "iam",
                List.of(new PermissionInput("iam:x:y", "x", false, false))));
        assertTrue(iam.catalog.rows.isEmpty());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void catalogTotalIsBounded() {
        for (int i = 0; i < PermissionCatalog.MAX_TOTAL; i++) iam.permission("other:p:p" + i, false, false);
        assertFailure(IamFailure.CONFLICT, () -> install.install(admin, "sales", List.of(READ)));
        assertTrue(iam.writes.isEmpty());
    }
}
