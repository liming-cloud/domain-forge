package com.company.leave.interfaces.http.leave;

/**
 * 提交、撤回、通过、驳回时携带读取时的申请版本。
 */
public record LeaveVersionRequest(long expectedVersion) {
}
