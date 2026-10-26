package org.domainforge.iam.interfaces.http.role;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.query.role.RoleView;

/**
 * 角色的 HTTP 表示。
 */
public record RoleResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        String code,
        String name,
        boolean enabled,
        boolean system,
        long version) {
    static RoleResponse of(RoleView view) {
        return new RoleResponse(view.id(), view.code(), view.name(), view.enabled(), view.system(), view.version());
    }
}
