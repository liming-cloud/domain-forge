package org.domainforge.iam.application.query.organization;

import java.util.List;

/**
 * 组织树查询；一套系统只有一棵树，按层级与 ID 排序整体返回。
 */
public interface OrganizationQueries {
    /**
     * 全部组织节点，父节点在子节点之前。
     */
    List<OrganizationUnitView> tree();
}
