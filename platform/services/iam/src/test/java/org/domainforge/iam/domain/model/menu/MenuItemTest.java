package org.domainforge.iam.domain.model.menu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.domainforge.iam.domain.RuleAssertions.assertRule;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 菜单树规则：按钮不能作父节点，页面和按钮须关联权限。
 */
class MenuItemTest {
    private static MenuItem item(long id, MenuType type, String permission) {
        return MenuItem.create(id, null, type, "m" + id, "菜单" + id, type == MenuType.BUTTON ? null : "/p" + id,
                0, permission);
    }

    @Test
    void pagesAndButtonsNeedPermission() {
        assertRule("MENU_PERMISSION_REQUIRED", () -> item(1, MenuType.PAGE, null));
        assertRule("MENU_PERMISSION_REQUIRED", () -> item(1, MenuType.BUTTON, null));
        assertDoesNotThrow(() -> item(1, MenuType.DIRECTORY, null));
        assertRule("MENU_BUTTON_PATH",
                () -> MenuItem.create(1, null, MenuType.BUTTON, "b1", "按钮", "/x", 0, "sales:order:read"));
    }

    @Test
    void parentRules() {
        MenuItem directory = item(1, MenuType.DIRECTORY, null);
        MenuItem page = item(2, MenuType.PAGE, "sales:order:read");
        MenuItem button = item(3, MenuType.BUTTON, "sales:order:export");
        assertDoesNotThrow(() -> page.requireParent(directory));
        assertDoesNotThrow(() -> button.requireParent(page));
        assertRule("MENU_BUTTON_PARENT", () -> button.requireParent(directory));
        assertRule("MENU_BUTTON_PARENT", () -> button.requireParent(null));
        assertRule("MENU_PARENT_INVALID", () -> page.requireParent(button));
    }

    @Test
    void moveRejectsCycleAndUnchangedUpdateKeepsVersion() {
        MenuItem directory = item(1, MenuType.DIRECTORY, null);
        assertRule("MENU_MOVE_CYCLE", () -> directory.moveTo(5L, List.of(5L, 1L)));
        assertSame(directory, directory.update("菜单1", "/p1", 0, null));
    }
}
