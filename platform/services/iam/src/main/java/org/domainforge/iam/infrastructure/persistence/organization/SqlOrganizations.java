package org.domainforge.iam.infrastructure.persistence.organization;

import org.domainforge.iam.domain.model.organization.OrganizationUnit;
import org.domainforge.iam.domain.repository.OrganizationRepository;
import org.domainforge.iam.infrastructure.persistence.operation.Coordination;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * MyBatis 组织仓储。
 */
@Repository
public class SqlOrganizations implements OrganizationRepository {
    private final OrganizationMapper mapper;
    private final Coordination coordination;

    /**
     * 绑定组织 Mapper 与协调锁。
     */
    public SqlOrganizations(OrganizationMapper mapper, Coordination coordination) {
        this.mapper = mapper;
        this.coordination = coordination;
    }

    @Override
    public void lockStructure() {
        coordination.lock("organization", true);
    }

    @Override
    public @Nullable OrganizationUnit lock(long id) {
        OrganizationUnitRow row = mapper.lock(id);
        return row == null ? null : row.toDomain();
    }

    @Override
    public List<Long> ancestors(long id) {
        return mapper.ancestors(id);
    }

    @Override
    public long existing(Collection<Long> ids) {
        return ids.isEmpty() ? 0 : mapper.existing(ids);
    }

    @Override
    public boolean nameTaken(long parentId, String name, long exceptId) {
        return mapper.sameName(parentId, name, exceptId) > 0;
    }

    @Override
    public void insert(OrganizationUnit unit) {
        mapper.insert(OrganizationUnitRow.from(unit));
    }

    @Override
    public boolean update(OrganizationUnit unit, long expected) {
        return mapper.update(OrganizationUnitRow.from(unit), expected) == 1;
    }

    @Override
    public void delete(long id) {
        mapper.delete(id);
    }

    @Override
    public boolean referenced(long id) {
        return mapper.references(id) > 0;
    }
}
