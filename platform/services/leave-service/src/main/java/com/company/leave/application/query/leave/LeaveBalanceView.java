package com.company.leave.application.query.leave;

/**
 * 员工的剩余年假天数；从未发放过年假为 0。
 */
public record LeaveBalanceView(long accountId, int remainingDays) {
}
