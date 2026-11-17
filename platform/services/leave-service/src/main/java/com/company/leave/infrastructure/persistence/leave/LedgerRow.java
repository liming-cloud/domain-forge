package com.company.leave.infrastructure.persistence.leave;

/**
 * 年假账户表投影；申请在 {@link RequestRow}。
 */
public record LedgerRow(long accountId, int remainingDays, long version) {
}
