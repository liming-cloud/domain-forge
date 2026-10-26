package org.domainforge.iam.infrastructure.query.role;

/**
 * 角色授予展开行。
 */
public record RoleGrantProjection(String permissionCode, String scope, Long unitId) {
}
