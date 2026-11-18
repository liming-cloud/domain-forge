package com.company.leave.interfaces.http.leave;

import com.company.leave.application.command.leave.LeaveChange;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 命令完成后的申请 ID、新版本与状态；ID 以十进制字符串传输，下一次命令携带新版本。
 */
public record LeaveChangeResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) long id, long version, String status) {
    static LeaveChangeResponse of(LeaveChange change) {
        return new LeaveChangeResponse(change.requestId(), change.version(), change.status());
    }
}
