package com.company.leave.domain.model.leave;

/**
 * 请假申请状态：草稿 → 待审 → 通过／驳回；草稿与待审可由申请人撤回。通过、驳回、撤回是终态。
 */
public enum LeaveStatus {
    DRAFT, PENDING, APPROVED, REJECTED, WITHDRAWN;

    /**
     * 是否占用请假时段：未结束流程（草稿、待审）与已通过的申请占用，驳回与撤回的不占用。
     */
    public boolean occupiesPeriod() {
        return this == DRAFT || this == PENDING || this == APPROVED;
    }
}
