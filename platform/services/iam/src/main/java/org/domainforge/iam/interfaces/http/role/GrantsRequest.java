package org.domainforge.iam.interfaces.http.role;

import org.domainforge.iam.domain.model.role.PermissionGrant;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 整体替换角色授予。
 *
 * @param expectedVersion 角色版本
 * @param grants          全部授予
 */
public record GrantsRequest(long expectedVersion, List<GrantBody> grants) {
    /**
     * 拒绝缺少必要字段。
     */
    public GrantsRequest {
        if (expectedVersion < 1 || grants == null || grants.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请求字段不完整");
        grants = List.copyOf(grants);
    }

    Set<PermissionGrant> toDomain() {
        Set<PermissionGrant> values = new HashSet<>();
        for (GrantBody grant : grants) values.add(grant.toDomain());
        return values;
    }
}
