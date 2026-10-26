package org.domainforge.iam.interfaces.http.registration;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.command.registration.InvitationIssued;

/**
 * 新邀请的 ID 与一次性秘密；秘密只在本响应中出现一次。
 */
public record InvitationResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) long invitationId, String secret) {
    /**
     * 从应用层结果转换。
     */
    static InvitationResponse of(InvitationIssued issued) {
        return new InvitationResponse(issued.invitationId(), issued.secret());
    }

    /**
     * 防止默认 record 字符串将秘密写入日志。
     */
    @Override
    public String toString() {
        return "InvitationResponse[invitationId=" + invitationId + "]";
    }
}
