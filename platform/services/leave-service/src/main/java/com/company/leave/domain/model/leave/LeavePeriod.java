package com.company.leave.domain.model.leave;

import org.jmolecules.ddd.annotation.ValueObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 请假时段：起止日期都包含在内，按自然日计天数；不可变，按值相等。
 *
 * @param start 第一天
 * @param end   最后一天，不早于第一天
 */
@ValueObject
public record LeavePeriod(LocalDate start, LocalDate end) {
    /**
     * 单次申请最多的天数。
     */
    public static final int MAX_DAYS = 366;

    /**
     * 起止日期必填，结束不早于开始，跨度不超过 {@link #MAX_DAYS} 天。
     */
    public LeavePeriod {
        if (start == null || end == null) throw LeaveRuleViolation.invalid("请假起止日期必填");
        if (end.isBefore(start)) throw LeaveRuleViolation.invalid("结束日期不能早于开始日期");
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS)
            throw LeaveRuleViolation.invalid("单次请假最多 " + MAX_DAYS + " 天");
    }

    /**
     * 请假天数：含起止两天的自然日数。
     */
    public int days() {
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    /**
     * 两个时段是否有共同的日子（含首尾相接的同一天）。
     */
    public boolean overlaps(LeavePeriod other) {
        return !end.isBefore(other.start) && !other.end.isBefore(start);
    }
}
