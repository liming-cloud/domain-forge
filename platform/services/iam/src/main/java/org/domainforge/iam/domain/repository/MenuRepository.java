package org.domainforge.iam.domain.repository;

import org.domainforge.iam.domain.model.menu.MenuItem;

import java.util.List;

/**
 * 菜单聚合仓储；结构变更须先取得菜单协调锁。
 */
public interface MenuRepository {
    /**
     * 菜单协调锁：创建、移动与删除串行，防止并发移动成环。
     */
    void lockStructure();

    MenuItem lock(long id);

    /**
     * 节点自身及全部祖先 ID，从自身开始；不存在返回空列表。
     */
    List<Long> ancestors(long id);

    /**
     * 菜单代码是否已被使用。
     */
    boolean codeTaken(String code);

    void insert(MenuItem item);

    void update(MenuItem item, long expectedVersion);

    void delete(long id);

    boolean hasChildren(long id);
}
