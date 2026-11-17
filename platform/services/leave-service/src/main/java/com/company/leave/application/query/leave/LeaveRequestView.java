package com.company.leave.application.query.leave;

import java.time.LocalDate;

/**
 * 请假申请列表投影；天数含起止两天，version 是提交命令时须携带的申请版本。
 */
public record LeaveRequestView(
        long id, long applicantId, long unitId, LocalDate startDate, LocalDate endDate, int days, String status,
        long version) {
}
