package org.domainforge.iam.application.command.menu;

import org.domainforge.iam.application.InMemoryIam;
import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.menu.MenuItem;
import org.domainforge.iam.domain.model.menu.MenuType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.domainforge.iam.application.FailureAssertions.assertFailure;
import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 菜单树管理：关联权限须在目录中，按钮只能挂在页面下，移动不成环，有下级时不能删除。
 */
class ManageMenuTest {
    private static final String READ = "sales:order:read";
    private final InMemoryIam iam = new InMemoryIam();
    private final ManageMenu menus = new ManageMenu(
            iam.authorization(), iam.menus, iam.catalog, iam.identifiers, iam.audit);
    private final String admin = iam.actor(10, true, "iam:menu:manage");

    ManageMenuTest() {
        iam.permission(READ, true, false);
    }

    private long create(Long parent, String type, String code, String path, String permission) {
        return menus.create(admin, new MenuInput(parent, type, code, code, path, 0, permission)).targetId();
    }

    @Test
    void createsDirectoryPageAndButton() {
        long directory = create(null, "DIRECTORY", "sales", null, null);
        long page = create(directory, "PAGE", "sales.orders", "/orders", READ);
        long button = create(page, "BUTTON", "sales.orders.export", null, READ);

        assertEquals(MenuType.BUTTON, iam.menus.lock(button).type());
        assertEquals(page, iam.menus.lock(button).parentId());
        assertEquals(List.of("menu.insert " + directory, "audit menu.create", "menu.insert " + page,
                "audit menu.create", "menu.insert " + button, "audit menu.create"), iam.writes);
    }

    @Test
    void createRejectsUnknownPermissionTakenCodeOrBadParent() {
        long directory = create(null, "DIRECTORY", "sales", null, null);
        iam.writes.clear();
        String operator = iam.actor(20, false, "iam:menu:manage");

        assertFailure(IamFailure.CONFLICT, () -> create(null, "PAGE", "orders", "/o", "sales:order:delete"));
        assertFailure(IamFailure.CONFLICT, () -> create(null, "DIRECTORY", "sales", null, null));
        assertFailure(IamFailure.CONFLICT, () -> create(404L, "PAGE", "orders", "/o", READ));
        assertRule("MENU_BUTTON_PARENT", () -> create(directory, "BUTTON", "export", null, READ));
        assertFailure(IamFailure.FORBIDDEN, () -> menus.create(operator,
                new MenuInput(null, "DIRECTORY", "other", "其他", null, 0, null)));
        assertEquals(1, iam.menus.rows.size());
        assertTrue(iam.writes.isEmpty());
    }

    @Test
    void updateChecksPermissionAndVersion() {
        long page = create(null, "PAGE", "orders", "/orders", READ);
        iam.writes.clear();

        assertFailure(IamFailure.CONFLICT, () -> menus.update(admin, page, 1, "订单", "/o", 1, "sales:x:y"));
        assertFailure(IamFailure.CONFLICT, () -> menus.update(admin, page, 2, "订单", "/o", 1, READ));
        assertTrue(iam.writes.isEmpty());

        assertEquals(new CommandResult(page, 2), menus.update(admin, page, 1, "订单", "/o", 1, READ));
        assertEquals("/o", iam.menus.lock(page).path());
    }

    @Test
    void moveRejectsCycleAndStateHidesItem() {
        long top = create(null, "DIRECTORY", "top", null, null);
        long child = create(top, "DIRECTORY", "child", null, null);
        iam.writes.clear();

        assertRule("MENU_MOVE_CYCLE", () -> menus.move(admin, top, 1, child));
        assertTrue(iam.writes.isEmpty());

        assertEquals(new CommandResult(child, 2), menus.move(admin, child, 1, null));
        assertEquals(new CommandResult(top, 2), menus.state(admin, top, 1, false));
        MenuItem saved = iam.menus.lock(top);
        assertFalse(saved.enabled());
    }

    @Test
    void deletesOnlyLeaf() {
        long top = create(null, "DIRECTORY", "top", null, null);
        long child = create(top, "DIRECTORY", "child", null, null);
        iam.writes.clear();

        assertFailure(IamFailure.CONFLICT, () -> menus.delete(admin, top, 1));
        assertTrue(iam.writes.isEmpty());

        menus.delete(admin, child, 1);
        assertFalse(iam.menus.rows.containsKey(child));
        assertEquals(List.of("menu.delete " + child, "audit menu.delete"), iam.writes);
    }
}
