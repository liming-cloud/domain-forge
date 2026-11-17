package com.company.leave.interfaces.http.leave;

import com.company.leave.application.query.leave.LeaveRequestView;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * 请假申请的 HTTP 表示；ID 以十进制字符串传输，日期为 ISO 格式，天数含首尾两天。
 */
public record LeaveRequestResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long applicantId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long unitId,
        LocalDate startDate,
        LocalDate endDate,
        int days,
        String status) {
    static LeaveRequestResponse of(LeaveRequestView view) {
        return new LeaveRequestResponse(view.id(), view.applicantId(), view.unitId(), view.startDate(),
                view.endDate(), view.days(), view.status());
    }
}
