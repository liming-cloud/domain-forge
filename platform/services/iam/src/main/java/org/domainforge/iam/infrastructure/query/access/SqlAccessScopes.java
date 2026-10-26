package org.domainforge.iam.infrastructure.query.access;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.query.access.AccessScope;
import org.domainforge.iam.application.query.access.AccessScopes;
import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.domainforge.iam.domain.model.role.PermissionGrant;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按精确权限合并启用角色的范围：任一 ALL 即 ALL；否则合并本人与有效组织集合。
 *
 * <p>无匹配授予时结果为空范围；超限明确失败，不截断后继续。
 */
@Repository
public class SqlAccessScopes implements AccessScopes {
    private final AccessScopeMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlAccessScopes(AccessScopeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, AccessScope> scopes(String digest, List<String> permissions) {
        Set<String> requested = new LinkedHashSet<>(validated(permissions));
        boolean subject = false;
        Set<String> scoped = new HashSet<>();
        Set<String> all = new HashSet<>();
        Set<String> self = new HashSet<>();
        Map<String, Set<Long>> units = new HashMap<>();
        for (ScopeProjection row : mapper.scopes(digest, requested)) {
            switch (row.kind()) {
                case "SUBJECT" -> subject = true;
                case "PERMISSION" -> scoped.add(row.permission());
                case "ALL" -> all.add(row.permission());
                case "SELF" -> self.add(row.permission());
                default -> units.computeIfAbsent(row.permission(), code -> new HashSet<>()).add(row.unitId());
            }
        }
        if (!subject) throw new IamException(IamFailure.UNAUTHENTICATED, "需要有效登录");
        if (!scoped.containsAll(requested))
            throw new IamException(IamFailure.INVALID_REQUEST, "权限不在目录中或不需要数据范围");
        Map<String, AccessScope> result = new HashMap<>();
        for (String code : requested)
            result.put(code, scope(all.contains(code), self.contains(code), units.getOrDefault(code, Set.of())));
        return result;
    }

    private static List<String> validated(List<String> permissions) {
        if (permissions == null || permissions.isEmpty() || permissions.size() > MAX_BATCH)
            throw new IamException(IamFailure.INVALID_REQUEST, "一次须查询1至32个权限");
        try {
            permissions.forEach(PermissionDefinition::requireCode);
        } catch (IllegalArgumentException | NullPointerException rejected) {
            throw new IamException(IamFailure.INVALID_REQUEST, "权限码格式无效");
        }
        return permissions;
    }

    private static AccessScope scope(boolean all, boolean self, Set<Long> unitIds) {
        Set<Long> visible = all ? Set.of() : unitIds;
        if (visible.size() > PermissionGrant.MAX_UNITS)
            throw new IamException(IamFailure.UNAVAILABLE, "数据范围超过1000个组织，拒绝截断");
        return new AccessScope(all, self, visible);
    }
}
