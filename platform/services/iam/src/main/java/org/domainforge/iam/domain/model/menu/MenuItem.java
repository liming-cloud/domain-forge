package org.domainforge.iam.domain.model.menu;

import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.shared.AggregateRoot;

import java.util.List;

/**
 * 菜单树节点；受保护页面和按钮关联真实权限。可见投影只服务展示，不授予 API 权限。
 */
public record MenuItem(
        long id,
        Long parentId,
        MenuType type,
        String code,
        String name,
        String path,
        int sortOrder,
        String permission,
        boolean enabled,
        long version)
        implements AggregateRoot {
    /**
     * 校验代码、名称、路径与权限关联。
     */
    public MenuItem {
        if (id <= 0 || version < 1 || type == null || (parentId != null && parentId == id))
            throw new IllegalArgumentException("菜单身份、父节点或版本无效");
        if (code == null || !code.matches("[a-z][a-z0-9:._-]{1,63}"))
            throw new IllegalArgumentException("菜单代码格式无效");
        name = Role.requireName(name);
        requireTarget(type, path, permission);
        if (sortOrder < 0 || sortOrder > 100_000) throw new IllegalArgumentException("排序值须为0至100000");
    }

    /**
     * 路径有界、按钮无路径；页面和按钮必须关联格式正确的权限。
     */
    private static void requireTarget(MenuType type, String path, String permission) {
        if (path != null && (path.isBlank() || path.length() > 256))
            throw new IllegalArgumentException("路径须为1至256个字符");
        if (type == MenuType.BUTTON && path != null) throw new IllegalArgumentException("按钮没有路径");
        if (permission != null) PermissionDefinition.requireCode(permission);
        if (type != MenuType.DIRECTORY && permission == null)
            throw new IllegalArgumentException("页面和按钮必须关联权限");
    }

    /**
     * 创建启用的菜单项。
     */
    public static MenuItem create(
            long id, Long parentId, MenuType type, String code, String name, String path, int sortOrder,
            String permission) {
        return new MenuItem(id, parentId, type, code, name, path, sortOrder, permission, true, 1);
    }

    /**
     * 父节点不能是按钮；按钮只能挂在页面下。
     */
    public void requireParent(MenuItem parent) {
        if (parent == null) {
            if (type == MenuType.BUTTON) throw new IllegalStateException("按钮必须挂在页面下");
            return;
        }
        if (parent.type == MenuType.BUTTON) throw new IllegalStateException("按钮不能作父节点");
        if (type == MenuType.BUTTON && parent.type != MenuType.PAGE)
            throw new IllegalStateException("按钮必须挂在页面下");
    }

    /**
     * 修改展示属性与关联权限；类型与代码不可修改。
     */
    public MenuItem update(String nextName, String nextPath, int nextOrder, String nextPermission) {
        MenuItem changed = new MenuItem(
                id, parentId, type, code, nextName, nextPath, nextOrder, nextPermission, enabled, version + 1);
        return changed.name.equals(name)
                && java.util.Objects.equals(changed.path, path)
                && changed.sortOrder == sortOrder
                && java.util.Objects.equals(changed.permission, permission) ? this : changed;
    }

    /**
     * 移到新父节点（null 为顶层）；新父节点及其祖先中不能出现本节点。
     */
    public MenuItem moveTo(Long newParent, List<Long> ancestorsOfParent) {
        if (ancestorsOfParent.contains(id)) throw new IllegalStateException("不能移到自身或下级之下");
        if (java.util.Objects.equals(parentId, newParent)) return this;
        return new MenuItem(id, newParent, type, code, name, path, sortOrder, permission, enabled, version + 1);
    }

    /**
     * 停用后本项及其下级不再出现在可见菜单中。
     */
    public MenuItem changeEnabled(boolean state) {
        if (enabled == state) return this;
        return new MenuItem(id, parentId, type, code, name, path, sortOrder, permission, state, version + 1);
    }
}
