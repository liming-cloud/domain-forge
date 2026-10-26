package org.domainforge.iam.application.query.menu;

import java.util.List;
import java.util.Set;

/**
 * 菜单查询合同。
 */
public interface MenuQueries {
    /**
     * 管理端全部菜单项，父节点在子节点之前，同级按排序值。
     */
    List<MenuView> all();

    /**
     * 当前权限可见的菜单：本项及全部祖先启用；页面和按钮须持有关联权限，目录须有可见下级或自身权限被持有。
     *
     * <p>可见菜单只服务展示，不授予 API 权限；后端仍按权限执行。
     */
    List<MenuView> visible(Set<String> permissions);
}
