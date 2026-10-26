package org.domainforge.iam.infrastructure.query.role;

import org.domainforge.iam.application.query.role.RoleDetail;
import org.domainforge.iam.application.query.role.RoleQueries;
import org.domainforge.iam.application.query.role.RoleView;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 角色查询实现。
 */
@Repository
public class SqlRoleQueries implements RoleQueries {
    private final RoleQueryMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlRoleQueries(RoleQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<RoleView> list() {
        return mapper.list();
    }

    @Override
    public RoleDetail detail(long id) {
        RoleView role = mapper.find(id);
        if (role == null) return null;
        var scopes = new LinkedHashMap<String, String>();
        var units = new LinkedHashMap<String, Set<Long>>();
        for (RoleGrantProjection row : mapper.grants(id)) {
            scopes.put(row.permissionCode(), row.scope());
            var set = units.computeIfAbsent(row.permissionCode(), code -> new HashSet<>());
            if (row.unitId() != null) set.add(row.unitId());
        }
        var grants = new ArrayList<RoleDetail.GrantView>();
        scopes.forEach((code, scope) -> grants.add(new RoleDetail.GrantView(code, scope, units.get(code))));
        return new RoleDetail(role, grants);
    }
}
