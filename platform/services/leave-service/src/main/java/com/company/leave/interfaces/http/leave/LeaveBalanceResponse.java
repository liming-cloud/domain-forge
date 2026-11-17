package com.company.leave.interfaces.http.leave;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 员工的剩余年假天数；账号 ID 以十进制字符串传输。
 */
public record LeaveBalanceResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long accountId, int remainingDays) {
}
