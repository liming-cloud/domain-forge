package org.domainforge.iam.application.query.role;

/**
 * 角色投影。
 */
public record RoleView(long id, String code, String name, boolean enabled, boolean system, long version) {
}
