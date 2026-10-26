package org.domainforge.iam.domain.model.menu;

/**
 * 菜单项类型：目录可含子项，页面可含按钮，按钮不能作父节点。
 */
public enum MenuType {
    DIRECTORY,
    PAGE,
    BUTTON
}
