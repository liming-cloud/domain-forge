package org.domainforge.iam.application.command.menu;

import org.domainforge.iam.domain.model.menu.MenuType;

/**
 * 创建菜单项的输入；类型与代码创建后不可修改。
 *
 * @param parentId   父节点，null 为顶层
 * @param type       类型
 * @param code       全局唯一代码
 * @param name       名称
 * @param path       路由路径，按钮为 null
 * @param sortOrder  同级排序值
 * @param permission 关联权限，目录可为 null
 */
public record MenuInput(
        Long parentId, MenuType type, String code, String name, String path, int sortOrder, String permission) {
}
