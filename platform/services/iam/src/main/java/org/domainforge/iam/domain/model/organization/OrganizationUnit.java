package org.domainforge.iam.domain.model.organization;

import org.domainforge.iam.domain.model.role.Role;
import org.domainforge.shared.AggregateRoot;

import java.util.List;

/**
 * 组织节点：一套系统一棵树，以 parentId 关联；根受保护，不能移动、停用或删除。
 *
 * <p>节点本身及全部祖先启用时才是有效部门；有效性由查询按当前结构计算，不在节点上冗余保存。
 */
public record OrganizationUnit(long id, Long parentId, String name, boolean enabled, long version)
        implements AggregateRoot {
    /**
     * 根组织的固定 ID，由数据库基线创建。
     */
    public static final long ROOT_ID = 1;

    /**
     * 只有根没有父节点，节点不能以自己为父。
     */
    public OrganizationUnit {
        if (id <= 0 || version < 1 || (parentId == null) != (id == ROOT_ID) || (parentId != null && parentId == id))
            throw new IllegalArgumentException("组织身份、父节点或版本无效");
        name = Role.requireName(name);
    }

    /**
     * 在指定父节点下创建启用的组织。
     */
    public static OrganizationUnit create(long id, long parentId, String name) {
        if (id == ROOT_ID) throw new IllegalArgumentException("不能创建根组织");
        return new OrganizationUnit(id, parentId, name, true, 1);
    }

    /**
     * 修改名称；同级重名由数据库唯一约束兜底。
     */
    public OrganizationUnit rename(String next) {
        String value = Role.requireName(next);
        if (name.equals(value)) return this;
        return new OrganizationUnit(id, parentId, value, enabled, version + 1);
    }

    /**
     * 停用只撤除由该组织归属产生的范围，不禁用账号或移除其角色；根不能停用。
     */
    public OrganizationUnit changeEnabled(boolean state) {
        if (enabled == state) return this;
        if (id == ROOT_ID) throw new IllegalStateException("根组织不能停用");
        return new OrganizationUnit(id, parentId, name, state, version + 1);
    }

    /**
     * 移到新父节点下；新父节点及其祖先中不能出现本节点，避免成环。
     *
     * @param newParent          新父节点 ID
     * @param ancestorsOfParent  新父节点自身及其全部祖先 ID
     */
    public OrganizationUnit moveTo(long newParent, List<Long> ancestorsOfParent) {
        if (id == ROOT_ID) throw new IllegalStateException("根组织不能移动");
        if (ancestorsOfParent.contains(id)) throw new IllegalStateException("不能移到自身或下级之下");
        if (parentId == newParent) return this;
        return new OrganizationUnit(id, newParent, name, enabled, version + 1);
    }

    /**
     * 根不能删除；子节点、成员与范围引用由调用方在组织协调锁内核对。
     */
    public void requireDeletable() {
        if (id == ROOT_ID) throw new IllegalStateException("根组织不能删除");
    }
}
