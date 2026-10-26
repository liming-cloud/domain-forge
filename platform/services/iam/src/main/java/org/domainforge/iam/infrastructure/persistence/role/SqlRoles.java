package org.domainforge.iam.infrastructure.persistence.role;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.role.DataScope;
import org.domainforge.iam.domain.model.role.PermissionGrant;
import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.iam.domain.repository.RoleRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MyBatis 角色仓储：角色与授予、自定义范围组织一起还原和整体替换。
 */
@Repository
public class SqlRoles implements RoleRepository {
    private final RoleMapper mapper;

    /**
     * 绑定角色 Mapper。
     */
    public SqlRoles(RoleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Role lock(long id) {
        RoleRow row = mapper.lock(id);
        if (row == null) return null;
        Map<String, String> scopes = new LinkedHashMap<>();
        Map<String, Set<Long>> units = new LinkedHashMap<>();
        for (GrantRow grant : mapper.grants(id)) {
            scopes.put(grant.permissionCode(), grant.scope());
            var set = units.computeIfAbsent(grant.permissionCode(), code -> new HashSet<>());
            if (grant.unitId() != null) set.add(grant.unitId());
        }
        Set<PermissionGrant> grants = new HashSet<>();
        scopes.forEach((code, scope) ->
                grants.add(new PermissionGrant(code, DataScope.valueOf(scope), units.get(code))));
        return new Role(row.id(), row.code(), row.name(), row.enabled(), row.system(), row.version(), grants);
    }

    @Override
    public boolean codeTaken(String code) {
        return mapper.codes(code) > 0;
    }

    @Override
    public void insert(Role role) {
        mapper.insert(row(role));
        writeGrants(role);
    }

    @Override
    public void update(Role role, long expected) {
        if (mapper.update(row(role), expected) != 1) throw new IamException(IamFailure.CONFLICT, "角色版本已变化");
        mapper.deleteGrants(role.id());
        writeGrants(role);
    }

    private static RoleRow row(Role role) {
        return new RoleRow(role.id(), role.code(), role.name(), role.enabled(), role.system(), role.version());
    }

    private void writeGrants(Role role) {
        if (role.grants().isEmpty()) return;
        List<GrantRow> grants = new ArrayList<>();
        List<GrantRow> units = new ArrayList<>();
        for (PermissionGrant grant : role.grants()) {
            grants.add(new GrantRow(grant.permission(), grant.scope().name(), null));
            for (long unit : grant.unitIds()) units.add(new GrantRow(grant.permission(), grant.scope().name(), unit));
        }
        mapper.insertGrants(role.id(), grants);
        if (!units.isEmpty()) mapper.insertGrantUnits(role.id(), units);
    }

    @Override
    public void delete(long id) {
        mapper.deleteGrants(id);
        mapper.delete(id);
    }

    @Override
    public long assignments(long id) {
        return mapper.assignments(id);
    }
}
