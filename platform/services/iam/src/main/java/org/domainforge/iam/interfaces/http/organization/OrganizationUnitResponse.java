package org.domainforge.iam.interfaces.http.organization;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.query.organization.OrganizationUnitView;
import org.domainforge.iam.interfaces.http.protocol.Ids;

/**
 * 组织节点的 HTTP 表示；effective 表示节点及全部祖先均启用。
 */
public record OrganizationUnitResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        String parentId,
        String name,
        boolean enabled,
        boolean effective,
        long version) {
    static OrganizationUnitResponse of(OrganizationUnitView view) {
        return new OrganizationUnitResponse(
                view.id(), Ids.string(view.parentId()), view.name(), view.enabled(), view.effective(), view.version());
    }
}
