package org.domainforge.iam.interfaces.http.registration;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 邀请创建或显式重发；替换ID为空表示新建。
 */
@Schema(description = "邀请创建或显式重发；替换ID为空表示新建")
public record InvitationRequest(UUID operationId, String loginName, UUID replacesInvitationId) {
    /**
     * 拒绝缺少命令必要字段；不会从空值推断写入意图。
     */
    public InvitationRequest {
        if (operationId == null || loginName == null) throw new IllegalArgumentException("请求字段不完整");
    }
}
