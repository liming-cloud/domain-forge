package com.company.leave.domain.model.leave;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * 年假规则的违反：错误码稳定，提示是固定说明，可以对外返回。
 */
public final class LeaveRuleViolation extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private LeaveRuleViolation(Kind kind, String code, String message) {
        super(kind, code, message);
    }

    /**
     * 字段取值违反规则（400）。
     */
    public static LeaveRuleViolation invalid(String message) {
        return new LeaveRuleViolation(Kind.INVALID_REQUEST, "LEAVE_INVALID", message);
    }

    /**
     * 申请当前状态不允许该操作（409）。
     */
    public static LeaveRuleViolation illegalTransition(String action) {
        return new LeaveRuleViolation(Kind.CONFLICT, "LEAVE_STATE_CONFLICT", "请假申请当前状态不允许" + action);
    }

    /**
     * 与同一人未结束或已通过的申请时段重叠（422）。
     */
    public static LeaveRuleViolation periodOverlap() {
        return new LeaveRuleViolation(Kind.UNPROCESSABLE, "LEAVE_PERIOD_OVERLAP", "请假时段与已有申请重叠");
    }

    /**
     * 年假余额不足以通过该申请（422）。
     */
    public static LeaveRuleViolation balanceInsufficient() {
        return new LeaveRuleViolation(Kind.UNPROCESSABLE, "LEAVE_BALANCE_INSUFFICIENT", "年假余额不足");
    }
}
