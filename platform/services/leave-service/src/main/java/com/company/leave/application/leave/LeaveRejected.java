package com.company.leave.application.leave;

import org.domainforge.shared.BusinessRuleViolation;

import java.io.Serial;

/**
 * 请假用例已判定的业务拒绝：带稳定错误码，由 web-support 按类别统一转为 Problem Details（404／409／400）。
 */
public final class LeaveRejected extends BusinessRuleViolation {
    @Serial
    private static final long serialVersionUID = 1L;

    private LeaveRejected(Kind kind, String code, String message) {
        super(kind, code, message);
    }

    /**
     * 申请不存在、不属于当前申请人，或不在审批人的部门范围内（404，不暴露他人申请是否存在）。
     */
    public static LeaveRejected requestNotFound() {
        return new LeaveRejected(Kind.NOT_FOUND, "LEAVE_REQUEST_NOT_FOUND", "请假申请不存在");
    }

    /**
     * 同一员工的年假账户已被并发修改（409 {@code VERSION_CONFLICT}，与平台相同），客户端刷新后重试。
     */
    public static LeaveRejected versionConflict() {
        return new LeaveRejected(Kind.CONFLICT, "VERSION_CONFLICT", "年假账户已被修改，请刷新后重试");
    }

    /**
     * 分页参数无效（400）。
     */
    public static LeaveRejected invalidPage() {
        return new LeaveRejected(Kind.INVALID_REQUEST, "LEAVE_INVALID", "分页游标须非负且大小须为1至100");
    }
}
