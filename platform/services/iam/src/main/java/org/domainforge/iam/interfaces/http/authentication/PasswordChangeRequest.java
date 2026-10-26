package org.domainforge.iam.interfaces.http.authentication;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 自助改密输入；需当前会话和旧密码。
 */
@Schema(description = "自助改密输入；需当前会话和旧密码")
public record PasswordChangeRequest(UUID operationId, String oldPassword, String newPassword) {
    /**
     * 拒绝缺少命令必要字段；不会从空值推断写入意图。
     */
    public PasswordChangeRequest {
        if (operationId == null || oldPassword == null || newPassword == null)
            throw new IllegalArgumentException("请求字段不完整");
    }

    /**
     * 禁止将敏感输入通过默认record字符串记录。
     */
    @Override
    public String toString() {
        return "PasswordChangeRequest[redacted]";
    }
}
