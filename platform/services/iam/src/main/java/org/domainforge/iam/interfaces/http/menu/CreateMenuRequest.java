package org.domainforge.iam.interfaces.http.menu;

import org.domainforge.iam.application.command.menu.MenuInput;
import org.domainforge.iam.domain.model.menu.MenuType;

/**
 * 创建菜单项。
 */
public record CreateMenuRequest(
        Long parentId, String type, String code, String name, String path, Integer sortOrder, String permission) {
    /**
     * 拒绝缺少必要字段。
     */
    public CreateMenuRequest {
        if (type == null || code == null || name == null || sortOrder == null)
            throw new IllegalArgumentException("请求字段不完整");
    }

    MenuInput toInput() {
        return new MenuInput(parentId, MenuType.valueOf(type), code, name, path, sortOrder, permission);
    }
}
