package org.domainforge.iam.infrastructure.persistence.menu;

import org.domainforge.iam.domain.model.menu.MenuItem;
import org.domainforge.iam.domain.model.menu.MenuType;

/**
 * 菜单表投影。
 */
public record MenuRow(
        long id,
        Long parentId,
        String type,
        String code,
        String name,
        String path,
        int sortOrder,
        String permissionCode,
        boolean enabled,
        long version) {
    static MenuRow from(MenuItem item) {
        return new MenuRow(
                item.id(), item.parentId(), item.type().name(), item.code(), item.name(), item.path(),
                item.sortOrder(), item.permission(), item.enabled(), item.version());
    }

    MenuItem toDomain() {
        return new MenuItem(
                id, parentId, MenuType.valueOf(type), code, name, path, sortOrder, permissionCode, enabled, version);
    }
}
