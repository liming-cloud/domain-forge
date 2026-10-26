package org.domainforge.iam.infrastructure.persistence.catalog;

import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 权限目录持久化 SQL。
 */
@org.apache.ibatis.annotations.Mapper
public interface CatalogMapper {
    List<PermissionRow> find(@Param("codes") Collection<String> codes);

    List<PermissionRow> namespace(String namespace);

    long countOutside(String namespace);

    /**
     * 被授予或菜单引用的权限码。
     */
    List<String> referenced(@Param("codes") Collection<String> codes);

    /**
     * 删除命名空间中不在保留集合内的权限；保留集合为空时删除全部。
     */
    int deleteExcept(@Param("namespace") String namespace, @Param("keep") Collection<String> keep);

    int upsert(@Param("items") List<PermissionRow> items);
}
