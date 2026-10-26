package org.domainforge.iam.application.query.catalog;

/**
 * 权限目录投影。
 */
public record PermissionView(String code, String name, boolean scoped, boolean systemOnly) {
}
