package org.domainforge.iam.infrastructure.persistence.organization;

import org.domainforge.iam.domain.model.organization.OrganizationUnit;

/**
 * 组织表投影。
 */
public record OrganizationUnitRow(long id, Long parentId, String name, boolean enabled, long version) {
    static OrganizationUnitRow from(OrganizationUnit unit) {
        return new OrganizationUnitRow(unit.id(), unit.parentId(), unit.name(), unit.enabled(), unit.version());
    }

    OrganizationUnit toDomain() {
        return new OrganizationUnit(id, parentId, name, enabled, version);
    }
}
