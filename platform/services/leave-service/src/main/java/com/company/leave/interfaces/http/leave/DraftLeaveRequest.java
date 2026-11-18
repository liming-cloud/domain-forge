package com.company.leave.interfaces.http.leave;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/**
 * 起草请假申请：挂靠的部门（IAM 组织 ID，十进制字符串）与起止日期（ISO 日期，含首尾两天）。
 */
public record DraftLeaveRequest(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long unitId,
        @Nullable LocalDate startDate,
        @Nullable LocalDate endDate) {
}
