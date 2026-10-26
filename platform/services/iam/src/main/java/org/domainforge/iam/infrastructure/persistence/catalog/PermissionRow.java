package org.domainforge.iam.infrastructure.persistence.catalog;

import org.domainforge.iam.domain.model.permission.PermissionDefinition;

/**
 * 权限目录表投影。
 */
public record PermissionRow(String code, String namespace, String name, boolean scoped, boolean systemOnly) {
    static PermissionRow from(PermissionDefinition definition) {
        return new PermissionRow(
                definition.code(), definition.namespace(), definition.name(), definition.scoped(),
                definition.systemOnly());
    }

    PermissionDefinition toDomain() {
        return new PermissionDefinition(code, name, scoped, systemOnly);
    }
}
