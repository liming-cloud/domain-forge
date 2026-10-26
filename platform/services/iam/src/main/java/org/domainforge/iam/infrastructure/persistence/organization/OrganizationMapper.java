package org.domainforge.iam.infrastructure.persistence.organization;

import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 组织持久化 SQL；祖先链用递归 CTE 计算。
 */
@org.apache.ibatis.annotations.Mapper
public interface OrganizationMapper {
    OrganizationUnitRow lock(long id);

    /**
     * 自身及全部祖先 ID，从自身开始。
     */
    List<Long> ancestors(long id);

    long existing(@Param("ids") Collection<Long> ids);

    long sameName(@Param("parent") long parent, @Param("name") String name, @Param("except") long except);

    int insert(OrganizationUnitRow row);

    int update(@Param("row") OrganizationUnitRow row, @Param("expected") long expected);

    int delete(long id);

    /**
     * 子节点、成员与自定义范围引用的合计。
     */
    long references(long id);
}
