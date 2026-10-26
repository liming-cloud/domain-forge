package org.domainforge.iam.application.query.menu;

/**
 * 菜单项投影。
 */
public record MenuView(
        long id,
        Long parentId,
        String type,
        String code,
        String name,
        String path,
        int sortOrder,
        String permission,
        boolean enabled,
        long version) {
}
