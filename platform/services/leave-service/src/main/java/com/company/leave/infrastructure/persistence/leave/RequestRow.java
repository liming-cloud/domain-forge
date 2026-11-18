package com.company.leave.infrastructure.persistence.leave;

import java.time.LocalDate;

/**
 * 请假申请表投影。
 */
public record RequestRow(long id, long unitId, LocalDate startDate, LocalDate endDate, String status, long version) {
}
