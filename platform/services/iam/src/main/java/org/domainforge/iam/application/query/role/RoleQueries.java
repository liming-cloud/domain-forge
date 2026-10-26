package org.domainforge.iam.application.query.role;

import java.util.List;

/**
 * 角色查询合同。
 */
public interface RoleQueries {
    /**
     * 全部角色，按 ID 排序；角色数量由系统规模决定，不分页。
     */
    List<RoleView> list();

    /**
     * 角色详情，不存在返回 null。
     */
    RoleDetail detail(long id);
}
