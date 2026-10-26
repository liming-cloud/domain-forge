package org.domainforge.iam.interfaces.http.catalog;

import org.domainforge.iam.application.query.catalog.PermissionView;
import org.domainforge.iam.domain.model.permission.PermissionDefinition;

/**
 * 权限目录项；请求与响应共用。外部命名空间不能声明 systemOnly。
 *
 * @param code       权限码
 * @param name       名称
 * @param scoped     授予时是否需要数据范围
 * @param systemOnly 是否系统专属（仅响应）
 */
public record PermissionBody(String code, String name, boolean scoped, boolean systemOnly) {
    static PermissionBody of(PermissionView view) {
        return new PermissionBody(view.code(), view.name(), view.scoped(), view.systemOnly());
    }

    PermissionDefinition toDomain() {
        return new PermissionDefinition(code, name, scoped, systemOnly);
    }
}
