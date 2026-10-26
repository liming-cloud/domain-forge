package org.domainforge.iam.infrastructure.query.catalog;

import org.domainforge.iam.application.query.catalog.PermissionQueries;
import org.domainforge.iam.application.query.catalog.PermissionView;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 权限目录查询实现。
 */
@Repository
public class SqlPermissionQueries implements PermissionQueries {
    private final PermissionQueryMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlPermissionQueries(PermissionQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<PermissionView> list() {
        return mapper.list();
    }
}
