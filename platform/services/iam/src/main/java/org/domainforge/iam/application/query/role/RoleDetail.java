package org.domainforge.iam.application.query.role;

import java.util.List;
import java.util.Set;

/**
 * 角色详情：基本信息与全部授予。
 */
public record RoleDetail(RoleView role, List<GrantView> grants) {
    /**
     * 复制集合，防止修改查询快照。
     */
    public RoleDetail {
        grants = List.copyOf(grants);
    }

    /**
     * 一项授予：权限、范围与自定义范围的组织。
     */
    public record GrantView(String permission, String scope, Set<Long> unitIds) {
        /**
         * 复制集合。
         */
        public GrantView {
            unitIds = Set.copyOf(unitIds);
        }
    }
}
