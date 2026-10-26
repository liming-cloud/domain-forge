package org.domainforge.iam.infrastructure.persistence.catalog;

import org.domainforge.iam.domain.model.permission.PermissionDefinition;
import org.domainforge.iam.domain.model.permission.PermissionNamespace;
import org.domainforge.iam.domain.repository.PermissionCatalog;
import org.domainforge.iam.infrastructure.persistence.operation.Coordination;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MyBatis 权限目录仓储。
 */
@Repository
public class SqlPermissionCatalog implements PermissionCatalog {
    private final CatalogMapper mapper;
    private final Coordination coordination;

    /**
     * 绑定目录 Mapper 与协调锁。
     */
    public SqlPermissionCatalog(CatalogMapper mapper, Coordination coordination) {
        this.mapper = mapper;
        this.coordination = coordination;
    }

    @Override
    public void lock(boolean exclusive) {
        coordination.lock("catalog", exclusive);
    }

    @Override
    public Map<String, PermissionDefinition> find(Collection<String> codes) {
        if (codes.isEmpty()) return Map.of();
        return mapper.find(codes).stream()
                .map(PermissionRow::toDomain)
                .collect(Collectors.toMap(PermissionDefinition::code, definition -> definition));
    }

    @Override
    public PermissionNamespace namespace(String namespace) {
        return new PermissionNamespace(namespace, mapper.namespace(namespace).stream()
                .map(PermissionRow::toDomain)
                .collect(Collectors.toSet()));
    }

    @Override
    public long countOutside(String namespace) {
        return mapper.countOutside(namespace);
    }

    @Override
    public Set<String> referenced(Collection<String> codes) {
        return codes.isEmpty() ? Set.of() : new HashSet<>(mapper.referenced(codes));
    }

    @Override
    public void replace(PermissionNamespace namespace) {
        var keep = namespace.permissions().stream().map(PermissionDefinition::code).toList();
        mapper.deleteExcept(namespace.namespace(), keep);
        if (!keep.isEmpty())
            mapper.upsert(namespace.permissions().stream().map(PermissionRow::from).toList());
    }
}
