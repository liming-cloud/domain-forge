package org.domainforge.iam.infrastructure;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.command.account.InitializeAdministrator;
import org.domainforge.iam.application.command.account.ManageAccount;
import org.domainforge.iam.application.command.account.MembershipInput;
import org.domainforge.iam.application.command.authentication.ChangePassword;
import org.domainforge.iam.application.command.authentication.Login;
import org.domainforge.iam.application.command.catalog.InstallPermissions;
import org.domainforge.iam.application.command.catalog.PermissionInput;
import org.domainforge.iam.application.command.menu.ManageMenu;
import org.domainforge.iam.application.command.menu.MenuInput;
import org.domainforge.iam.application.command.organization.ManageOrganization;
import org.domainforge.iam.application.command.registration.AcceptInvitation;
import org.domainforge.iam.application.command.registration.IssueInvitation;
import org.domainforge.iam.application.command.role.GrantInput;
import org.domainforge.iam.application.command.role.ManageRole;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.query.access.AccessScope;
import org.domainforge.iam.application.query.access.AccessScopes;
import org.domainforge.iam.application.query.menu.MenuQueries;
import org.domainforge.iam.application.query.menu.MenuView;
import org.domainforge.iam.application.query.organization.OrganizationQueries;
import org.domainforge.iam.domain.IamRuleViolation;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.organization.OrganizationUnit;
import org.domainforge.iam.domain.model.role.DataScope;
import org.domainforge.iam.domain.model.shared.SystemRole;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.infrastructure.persistence.fixture.IdentityFixtureMapper;
import org.domainforge.shared.BusinessRuleViolation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整身份权限模型在真实 PostgreSQL 上的行为：组织树、角色授予、数据范围、权限目录、菜单与系统管理员保全。
 */
class AccessModelIT {
    private static final String PASSWORD = "A private registration 密码 2026!";
    private static final String NEW_PASSWORD = "Another private 密码 for 2027!";
    private static final String ADMIN = "initial.admin";
    private static final String READ = "sales:order:read";
    private static final String EXPORT = "sales:order:export";
    private static ConfigurableApplicationContext application;
    private static IdentityFixtureMapper sql;
    private String admin;

    @BeforeAll
    static void start() {
        application = SharedIamApplication.get();
        sql = application.getBean(IdentityFixtureMapper.class);
    }

    @BeforeEach
    void reset() {
        sql.reset();
        bean(StringRedisTemplate.class).execute((RedisCallback<Void>) AccessModelIT::flush);
        bean(InitializeAdministrator.class).execute(ADMIN, PASSWORD);
        admin = digest(ADMIN);
        bean(InstallPermissions.class).install(admin, "sales", List.of(
                new PermissionInput(READ, "查询订单", true, false),
                new PermissionInput(EXPORT, "导出订单", false, false)));
    }

    private static Void flush(RedisConnection connection) {
        connection.serverCommands().flushDb();
        return null;
    }

    private static <T> T bean(Class<T> type) {
        return application.getBean(type);
    }

    private static CurrentIdentity identity(String name) {
        String token = bean(Login.class).execute(name, PASSWORD, "127.0.0.20").token();
        return bean(CurrentAuthorization.class).current(token);
    }

    private static String digest(String name) {
        return identity(name).tokenDigest();
    }

    private static void assertFailure(IamFailure expected, Executable command) {
        assertEquals(expected, failureOf(assertThrows(RuntimeException.class, command)));
    }

    // 应用层拒绝与领域规则违反按同一分类比较：违反的 400／409 类别对应 INVALID_REQUEST／CONFLICT。
    private static IamFailure failureOf(Exception failure) {
        if (failure instanceof IamRuleViolation rule)
            return rule.kind() == BusinessRuleViolation.Kind.CONFLICT ? IamFailure.CONFLICT
                    : IamFailure.INVALID_REQUEST;
        return assertInstanceOf(IamException.class, failure).failure();
    }

    private static Account account(String name) {
        return bean(AccountRepository.class).findByLoginName(name);
    }

    private long member(String name) {
        String secret = bean(IssueInvitation.class).execute(admin, name, null).secret();
        return bean(AcceptInvitation.class).execute(secret, name, PASSWORD).targetId();
    }

    private long unit(long parent, String name) {
        return bean(ManageOrganization.class).create(admin, parent, name).targetId();
    }

    private long role(String code, GrantInput... grants) {
        long id = bean(ManageRole.class).create(admin, code, code).targetId();
        bean(ManageRole.class).grants(admin, id, 1, List.of(grants));
        return id;
    }

    private void assign(String name, Long... roles) {
        Account target = account(name);
        bean(ManageAccount.class).roles(admin, target.id(), target.version(), Set.of(roles));
    }

    private void place(String name, long primary, Long... others) {
        Account target = account(name);
        var memberships = new java.util.ArrayList<MembershipInput>();
        memberships.add(new MembershipInput(primary, true));
        for (long other : others) memberships.add(new MembershipInput(other, false));
        bean(ManageAccount.class).memberships(admin, target.id(), target.version(), memberships);
    }

    private static GrantInput grant(String permission, DataScope scope, Long... units) {
        return new GrantInput(permission, scope.name(), Set.of(units));
    }

    private static long version(long unitId) {
        return bean(OrganizationQueries.class).tree().stream()
                .filter(view -> view.id() == unitId).findFirst().orElseThrow().version();
    }

    private static AccessScope scope(String name) {
        return bean(AccessScopes.class).scopes(digest(name), List.of(READ)).get(READ);
    }

    // 只改密码或启用状态不重写角色与成员归属子表（行的 ctid 不变）；只改成员归属时只重写成员子表。
    @Test
    void accountUpdateRewritesOnlyChangedChildRows() {
        long east = unit(OrganizationUnit.ROOT_ID, "华东");
        long west = unit(OrganizationUnit.ROOT_ID, "华西");
        long id = member("rows.member");
        assign("rows.member", role("ROWS", grant(READ, DataScope.DEPARTMENT)));
        place("rows.member", east);
        List<String> rows = sql.childRows(id);
        assertEquals(2, rows.size());

        bean(ChangePassword.class).execute(identity("rows.member"), PASSWORD, NEW_PASSWORD);
        var state = bean(ManageAccount.class);
        state.state(admin, id, account("rows.member").version(), false);
        state.state(admin, id, account("rows.member").version(), true);
        assertEquals(rows, sql.childRows(id));

        place("rows.member", west);
        List<String> moved = sql.childRows(id);
        assertNotEquals(rows.getFirst(), moved.getFirst());
        assertEquals(rows.getLast(), moved.getLast());
    }

    // 组织树：同级不重名、不成环、根受保护、有下级或成员时不能删除。
    @Test
    void organizationTreeRules() {
        var manage = bean(ManageOrganization.class);
        long east = unit(OrganizationUnit.ROOT_ID, "华东");
        long shanghai = unit(east, "上海");
        assertFailure(IamFailure.CONFLICT, () -> manage.create(admin, east, "上海"));
        assertFailure(IamFailure.CONFLICT, () -> manage.move(admin, east, version(east), shanghai));
        assertFailure(IamFailure.CONFLICT, () -> manage.state(admin, OrganizationUnit.ROOT_ID, 1, false));
        assertFailure(IamFailure.CONFLICT, () -> manage.delete(admin, east, version(east)));
        member("org.member");
        place("org.member", shanghai);
        assertFailure(IamFailure.CONFLICT, () -> manage.delete(admin, shanghai, version(shanghai)));
        Account placed = account("org.member");
        bean(ManageAccount.class).memberships(admin, placed.id(), placed.version(), List.of());
        manage.delete(admin, shanghai, version(shanghai));
        manage.delete(admin, east, version(east));
        assertEquals(1, bean(OrganizationQueries.class).tree().size());
        assertEquals(2, sql.auditCount("org.delete"));
    }

    // 授予须符合目录：未知权限、范围声明不符、系统专属权限、不存在的组织都被拒绝。
    @Test
    void grantsFollowCatalog() {
        var manage = bean(ManageRole.class);
        long id = manage.create(admin, "SALES", "销售").targetId();
        assertFailure(IamFailure.INVALID_REQUEST, () -> manage.grants(admin, id, 1,
                List.of(grant("sales:order:delete", DataScope.ALL))));
        assertFailure(IamFailure.INVALID_REQUEST, () -> manage.grants(admin, id, 1,
                List.of(grant(READ, DataScope.NOT_APPLICABLE))));
        assertFailure(IamFailure.INVALID_REQUEST, () -> manage.grants(admin, id, 1,
                List.of(grant(EXPORT, DataScope.ALL))));
        assertFailure(IamFailure.INVALID_REQUEST, () -> manage.grants(admin, id, 1,
                List.of(grant("iam:org:manage", DataScope.NOT_APPLICABLE))));
        assertFailure(IamFailure.CONFLICT, () -> manage.grants(admin, id, 1,
                List.of(grant(READ, DataScope.CUSTOM_DEPARTMENTS, 999L))));
        assertFailure(IamFailure.CONFLICT, () -> manage.create(admin, "sales", "重复代码"));
        assertEquals(2, manage.grants(admin, id, 1, List.of(grant(READ, DataScope.SELF))).version());
    }

    // 角色与权限在下一次检查即生效：分配后可见、停用角色后消失，不需要重新登录。
    @Test
    void rolesApplyOnNextCheck() {
        member("role.member");
        String token = bean(Login.class).execute("role.member", PASSWORD, "127.0.0.21").token();
        var authorization = bean(CurrentAuthorization.class);
        assertTrue(authorization.current(token).permissions().isEmpty());
        long sales = role("SALES", grant(EXPORT, DataScope.NOT_APPLICABLE));
        assign("role.member", sales);
        assertEquals(Set.of(EXPORT), authorization.current(token).permissions());
        assertEquals(Set.of("SALES"), authorization.current(token).roles());
        bean(ManageRole.class).state(admin, sales, 2, false);
        assertTrue(authorization.current(token).permissions().isEmpty());
        assertTrue(authorization.current(token).roles().isEmpty());
    }

    // 数据范围：本部门、含下级、自定义合并；停用组织使其及下级失效；任一 ALL 即全部；无授予为空。
    @Test
    void scopesMergeEffectiveUnits() {
        long east = unit(OrganizationUnit.ROOT_ID, "华东");
        long shanghai = unit(east, "上海");
        long west = unit(OrganizationUnit.ROOT_ID, "华西");
        member("scope.member");
        assertTrue(scope("scope.member").unitIds().isEmpty());
        place("scope.member", east);
        long department = role("DEPT", grant(READ, DataScope.DEPARTMENT));
        assign("scope.member", department);
        assertEquals(Set.of(east), scope("scope.member").unitIds());
        bean(ManageRole.class).grants(admin, department, 2, List.of(grant(READ, DataScope.DEPARTMENT_AND_DESCENDANTS)));
        long custom = role("CUSTOM", grant(READ, DataScope.CUSTOM_DEPARTMENTS, west));
        assign("scope.member", department, custom);
        assertEquals(Set.of(east, shanghai, west), scope("scope.member").unitIds());
        bean(ManageOrganization.class).state(admin, east, version(east), false);
        AccessScope narrowed = scope("scope.member");
        assertEquals(Set.of(west), narrowed.unitIds());
        assertFalse(narrowed.all());
        long all = role("ALL", grant(READ, DataScope.ALL));
        assign("scope.member", department, custom, all);
        assertTrue(scope("scope.member").all());
        assertFailure(IamFailure.INVALID_REQUEST,
                () -> bean(AccessScopes.class).scopes(digest("scope.member"), List.of(EXPORT)));
        assertFailure(IamFailure.UNAUTHENTICATED,
                () -> bean(AccessScopes.class).scopes("0".repeat(64), List.of(READ)));
    }

    // 目录升级：被引用的权限不能移除或改变范围声明，未引用的可以移除。
    @Test
    void catalogKeepsReferencedPermissions() {
        role("SALES", grant(READ, DataScope.SELF));
        var install = bean(InstallPermissions.class);
        var readOnly = new PermissionInput(READ, "查询订单", true, false);
        assertEquals(1, install.install(admin, "sales", List.of(readOnly)));
        assertFailure(IamFailure.CONFLICT, () -> install.install(admin, "sales", List.of()));
        assertFailure(IamFailure.CONFLICT, () -> install.install(admin, "sales",
                List.of(new PermissionInput(READ, "查询订单", false, false))));
        assertEquals(1, sql.auditCount("catalog.install") - 1);
    }

    // 可见菜单：页面与按钮按持有权限显示，目录随可见下级显示，停用目录隐藏整棵子树。
    @Test
    void menuVisibilityFollowsPermissions() {
        var manage = bean(ManageMenu.class);
        long directory = manage.create(admin, new MenuInput(null, "DIRECTORY", "sales", "销售", null, 0, null))
                .targetId();
        long page = manage.create(admin, new MenuInput(directory, "PAGE", "sales.orders", "订单", "/orders", 1,
                READ)).targetId();
        manage.create(admin, new MenuInput(page, "BUTTON", "sales.orders.export", "导出", null, 0, EXPORT));
        assertFailure(IamFailure.CONFLICT, () -> manage.create(admin,
                new MenuInput(directory, "BUTTON", "sales.bad", "坏按钮", null, 0, EXPORT)));
        var queries = bean(MenuQueries.class);
        assertEquals(List.of("sales", "sales.orders"), codes(queries.visible(Set.of(READ))));
        assertEquals(List.of("sales", "sales.orders", "sales.orders.export"),
                codes(queries.visible(Set.of(READ, EXPORT))));
        assertTrue(queries.visible(Set.of(EXPORT)).isEmpty());
        manage.state(admin, directory, 1, false);
        assertTrue(queries.visible(Set.of(READ, EXPORT)).isEmpty());
        assertFailure(IamFailure.CONFLICT, () -> bean(InstallPermissions.class)
                .install(admin, "sales", List.of()));
    }

    private static List<String> codes(List<MenuView> views) {
        return views.stream().map(MenuView::code).toList();
    }

    // 系统管理员保全：唯一系统管理员不能撤下系统角色，系统角色不能停用；普通持权者不能执行系统专属操作。
    @Test
    void systemAdministratorIsPreserved() {
        Account system = account(ADMIN);
        var manage = bean(ManageAccount.class);
        assertFailure(IamFailure.CONFLICT, () -> manage.roles(admin, system.id(), system.version(), Set.of()));
        assertFailure(IamFailure.CONFLICT, () -> bean(ManageRole.class).state(admin, SystemRole.ADMIN_ID, 1, false));
        member("reader");
        assign("reader", role("ORG_READER", grant("iam:org:read", DataScope.NOT_APPLICABLE)));
        String reader = digest("reader");
        assertFailure(IamFailure.FORBIDDEN, () -> bean(ManageOrganization.class)
                .create(reader, OrganizationUnit.ROOT_ID, "越权"));
        assertEquals(1L, bean(AccountRepository.class).systemAdministrators());
    }

    // 角色删除：启用中或仍有分配时拒绝，停用且撤销分配后可以删除。
    @Test
    void roleDeletionRequiresDisabledAndUnassigned() {
        member("holder");
        long sales = role("SALES", grant(READ, DataScope.SELF));
        assign("holder", sales);
        var manage = bean(ManageRole.class);
        assertFailure(IamFailure.CONFLICT, () -> manage.delete(admin, sales, 2));
        manage.state(admin, sales, 2, false);
        assertFailure(IamFailure.CONFLICT, () -> manage.delete(admin, sales, 3));
        assign("holder");
        manage.delete(admin, sales, 3);
        assertEquals(1, sql.auditCount("role.delete"));
    }
}
