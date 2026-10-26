package org.domainforge.iam.interfaces.http.role;

import org.domainforge.iam.application.query.role.RoleDetail;
import org.domainforge.iam.interfaces.http.protocol.Ids;

import java.util.List;

/**
 * 角色详情的 HTTP 表示。
 */
public record RoleDetailResponse(RoleResponse role, List<GrantBody> grants) {
    static RoleDetailResponse of(RoleDetail detail) {
        return new RoleDetailResponse(
                RoleResponse.of(detail.role()),
                detail.grants().stream()
                        .map(grant -> new GrantBody(grant.permission(), grant.scope(), Ids.strings(grant.unitIds())))
                        .toList());
    }
}
