package org.domainforge.iam.infrastructure.query.organization;

import org.domainforge.iam.application.query.organization.OrganizationUnitView;

import java.util.List;

/**
 * 组织树查询 SQL。
 */
@org.apache.ibatis.annotations.Mapper
public interface OrganizationQueryMapper {
    /**
     * 递归计算每个节点的有效性，父节点在前。
     */
    List<OrganizationUnitView> tree();
}
