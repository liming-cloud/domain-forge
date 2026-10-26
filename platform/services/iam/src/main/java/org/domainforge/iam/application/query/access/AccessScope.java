package org.domainforge.iam.application.query.access;

import java.util.Set;

/**
 * 某个精确权限对当前主体的有效数据范围，已合并全部启用角色的授予。
 *
 * @param all     是否为 ALL（限于本系统和该权限的资源类型）
 * @param self    是否包含本人数据
 * @param unitIds 可访问数据所属的有效组织；无授予时三者均为空，不能视为放行
 */
public record AccessScope(boolean all, boolean self, Set<Long> unitIds) {
    /**
     * 复制集合。
     */
    public AccessScope {
        unitIds = Set.copyOf(unitIds);
    }
}
