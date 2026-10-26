package org.domainforge.iam.interfaces.http.registration;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 邀请注册输入；凭据与秘密不进入日志。
 */
@Schema(description = "邀请注册输入；凭据与秘密不进入日志")
public record RegistrationRequest(String invitationSecret, String loginName, String password) {
    /**
     * 拒绝缺少命令必要字段；不会从空值推断写入意图。
     */
    public RegistrationRequest {
        if (invitationSecret == null || loginName == null || password == null)
            throw new IllegalArgumentException("请求字段不完整");
    }

    /**
     * 禁止将敏感输入通过默认 record 字符串记录。
     */
    @Override
    public String toString() {
        return "RegistrationRequest[redacted]";
    }
}
