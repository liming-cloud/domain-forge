package com.company.leave.application.command.leave;

/**
 * 命令完成后的申请 ID 与状态。
 */
public record LeaveChange(long requestId, String status) {
}
