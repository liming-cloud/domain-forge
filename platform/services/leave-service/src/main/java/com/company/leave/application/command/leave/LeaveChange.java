package com.company.leave.application.command.leave;

/**
 * 命令完成后的申请 ID、新版本与状态。
 */
public record LeaveChange(long requestId, long version, String status) {
}
