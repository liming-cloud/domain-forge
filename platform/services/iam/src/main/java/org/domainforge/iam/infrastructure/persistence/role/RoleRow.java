package org.domainforge.iam.infrastructure.persistence.role;

/**
 * 角色表投影。
 */
public record RoleRow(long id, String code, String name, boolean enabled, boolean system, long version) {
}
