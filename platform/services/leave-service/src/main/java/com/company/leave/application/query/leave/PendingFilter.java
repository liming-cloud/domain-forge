package com.company.leave.application.query.leave;

import com.company.leave.application.leave.LeaveRejected;

import java.util.Set;

/**
 * 待审批列表的条件：范围来自可信服务端（IAM），不是客户端输入。
 *
 * @param all   ALL 范围
 * @param units 可审批的部门
 * @param after 键分页游标
 * @param limit 1 至 100
 */
public record PendingFilter(boolean all, Set<Long> units, long after, int limit) {
    /**
     * 复制部门集合并校验分页。
     */
    public PendingFilter {
        units = Set.copyOf(units);
        if (after < 0 || limit < 1 || limit > 100) throw LeaveRejected.invalidPage();
    }
}
