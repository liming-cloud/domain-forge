package org.domainforge.iam.application.query.organization;

/**
 * 组织节点投影；effective 表示节点及全部祖先均启用。
 */
public record OrganizationUnitView(
        long id, Long parentId, String name, boolean enabled, boolean effective, long version) {
}
