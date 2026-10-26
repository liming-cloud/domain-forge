package org.domainforge.iam.infrastructure.query.menu;

import org.domainforge.iam.application.query.menu.MenuView;

import java.util.List;

/**
 * 菜单查询 SQL。
 */
@org.apache.ibatis.annotations.Mapper
public interface MenuQueryMapper {
    /**
     * 全部菜单项，父节点在前、同级按排序值与 ID。
     */
    List<MenuView> all();
}
