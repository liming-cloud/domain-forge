package com.company.leave.domain.model.leave;

import org.jmolecules.ddd.annotation.Entity;
import org.jmolecules.ddd.annotation.Identity;

/**
 * 一份年假申请：只存在于申请人的 {@link LeaveLedger} 内部，经聚合根改变状态；时段与所属部门创建后不变。
 *
 * @param id      申请身份，全局唯一
 * @param unitId  申请所属部门（IAM 组织 ID），决定哪些审批人能审批
 * @param period  请假时段
 * @param status  当前状态
 * @param version 申请版本，起草时为 1，每次状态迁移加一；客户端提交命令时携带，用于核对读取后是否已被修改
 */
@Entity
public record LeaveRequest(@Identity long id, long unitId, LeavePeriod period, LeaveStatus status, long version) {
    /**
     * 恢复时也校验身份、部门、时段与状态。
     */
    public LeaveRequest {
        if (id <= 0 || unitId <= 0 || period == null || status == null || version < 1)
            throw LeaveRuleViolation.invalid("请假申请身份、部门、时段、状态或版本无效");
    }

    /**
     * 草稿提交审批。
     */
    LeaveRequest submit() {
        return move(LeaveStatus.DRAFT, LeaveStatus.PENDING, "提交");
    }

    /**
     * 待审的申请通过。
     */
    LeaveRequest approve() {
        return move(LeaveStatus.PENDING, LeaveStatus.APPROVED, "审批通过");
    }

    /**
     * 待审的申请驳回。
     */
    LeaveRequest reject() {
        return move(LeaveStatus.PENDING, LeaveStatus.REJECTED, "驳回");
    }

    /**
     * 审批前（草稿或待审）撤回。
     */
    LeaveRequest withdraw() {
        if (status != LeaveStatus.DRAFT && status != LeaveStatus.PENDING)
            throw LeaveRuleViolation.illegalTransition("撤回");
        return new LeaveRequest(id, unitId, period, LeaveStatus.WITHDRAWN, version + 1);
    }

    private LeaveRequest move(LeaveStatus from, LeaveStatus to, String action) {
        if (status != from) throw LeaveRuleViolation.illegalTransition(action);
        return new LeaveRequest(id, unitId, period, to, version + 1);
    }
}
