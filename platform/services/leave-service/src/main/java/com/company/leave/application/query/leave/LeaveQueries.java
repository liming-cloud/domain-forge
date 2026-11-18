package com.company.leave.application.query.leave;

import java.util.List;

/**
 * 请假查询合同，实现直接使用本服务 SQL 投影；列表按申请 ID 升序键分页，游标非负、大小 1 至 100，否则 400。
 */
public interface LeaveQueries {
    /**
     * 本人的全部申请；只需登录，是不经过编排的纯查询。
     */
    List<LeaveRequestView> mine(long applicantId, long after, int limit);

    /**
     * 范围内部门的待审申请。
     */
    List<LeaveRequestView> pending(PendingFilter filter);

    /**
     * 员工的剩余年假天数；没有账户时为 0。
     */
    LeaveBalanceView balance(long accountId);
}
