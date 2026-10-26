package org.domainforge.iam.domain.repository;

import org.domainforge.iam.domain.model.organization.OrganizationUnit;

import java.util.Collection;
import java.util.List;

/**
 * 组织聚合仓储；结构变更须先取得组织协调锁。
 */
public interface OrganizationRepository {
    /**
     * 取得组织协调锁：组织结构、成员归属与自定义范围引用的写入在同一把锁下串行。
     */
    void lockStructure();

    /**
     * 锁定组织节点，不存在返回 null。
     */
    OrganizationUnit lock(long id);

    /**
     * 节点自身及全部祖先 ID，从自身开始；节点不存在返回空列表。
     */
    List<Long> ancestors(long id);

    /**
     * 给定 ID 中实际存在的数量。
     */
    long existing(Collection<Long> ids);

    /**
     * 同一父节点下是否已有同名组织（不含指定节点）。
     */
    boolean nameTaken(long parentId, String name, long exceptId);

    void insert(OrganizationUnit unit);

    /**
     * 按原版本保存，零行报告冲突。
     */
    void update(OrganizationUnit unit, long expectedVersion);

    void delete(long id);

    /**
     * 是否仍有子节点、成员或自定义范围引用，任一存在则不能删除。
     */
    boolean referenced(long id);
}
