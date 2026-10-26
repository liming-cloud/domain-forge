package org.domainforge.iam.infrastructure.query.organization;

import org.domainforge.iam.application.query.organization.OrganizationQueries;
import org.domainforge.iam.application.query.organization.OrganizationUnitView;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 组织树查询实现。
 */
@Repository
public class SqlOrganizationQueries implements OrganizationQueries {
    private final OrganizationQueryMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlOrganizationQueries(OrganizationQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<OrganizationUnitView> tree() {
        return mapper.tree();
    }
}
