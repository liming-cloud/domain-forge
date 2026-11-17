package com.company.leave.application.query.leave;

import java.time.LocalDate;

/**
 * 请假申请列表投影；天数含起止两天。
 */
public record LeaveRequestView(
        long id, long applicantId, long unitId, LocalDate startDate, LocalDate endDate, int days, String status) {
}
