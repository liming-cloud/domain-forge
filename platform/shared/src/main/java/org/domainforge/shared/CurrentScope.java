package org.domainforge.shared;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Set;

/**
 * 当前主体对一个精确权限的数据范围，已合并全部有效角色的授予；不构成对任意资源的授权。
 *
 * <p>all 为 true 时范围是本系统内该权限资源类型的全部数据；否则只包含 self（本人数据）与 unitIds（所属有效组织）。
 * 三者都为空表示没有授予，调用方必须按空结果处理，不能视为放行。组织 ID 以十进制字符串传输。
 *
 * @param all     是否全部数据
 * @param self    是否包含本人数据
 * @param unitIds 可访问数据所属的组织 ID
 */
public record CurrentScope(
        boolean all, boolean self, @JsonFormat(shape = JsonFormat.Shape.STRING) Set<Long> unitIds) {
    /**
     * 单项范围最多包含的组织数。
     */
    public static final int MAX_UNITS = 1000;

    /**
     * 组织 ID 为正数且不超过上限；all 时不携带组织。
     */
    public CurrentScope {
        if (unitIds == null
                || unitIds.size() > MAX_UNITS
                || (all && !unitIds.isEmpty())
                || unitIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("数据范围合同无效");
        }
        unitIds = Set.copyOf(unitIds);
    }

    /**
     * 没有任何授予的空范围。
     */
    public static CurrentScope none() {
        return new CurrentScope(false, false, Set.of());
    }

    /**
     * 是否为空范围。
     */
    public boolean empty() {
        return !all && !self && unitIds.isEmpty();
    }
}
