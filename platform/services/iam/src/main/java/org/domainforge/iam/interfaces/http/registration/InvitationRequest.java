package org.domainforge.iam.interfaces.http.registration;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 邀请创建或显式重发；替换 ID 为空表示新建，非空时以十进制字符串传入。
 */
@Schema(description = "邀请创建或显式重发；replacesInvitationId为空表示新建")
public record InvitationRequest(
        String loginName, @JsonFormat(shape = JsonFormat.Shape.STRING) Long replacesInvitationId) {
    /**
     * 拒绝缺少命令必要字段；不会从空值推断写入意图。
     */
    public InvitationRequest {
        if (loginName == null) throw new IllegalArgumentException("请求字段不完整");
    }
}
