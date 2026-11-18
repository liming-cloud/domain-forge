package com.company.leave.application.command.leave;

/**
 * 发放完成后的员工账号与剩余天数。
 */
public record BalanceChange(long accountId, int remainingDays) {
}
