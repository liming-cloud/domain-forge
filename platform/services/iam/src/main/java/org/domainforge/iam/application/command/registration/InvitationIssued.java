package org.domainforge.iam.application.command.registration;

/**
 * 新邀请的 ID 与一次性秘密；秘密只在创建时返回一次，数据库只保存其摘要。
 *
 * @param invitationId 邀请 ID，重发时用于指明被替换的邀请
 * @param secret       交给受邀者的注册秘密
 */
public record InvitationIssued(long invitationId, String secret) {
    /**
     * 防止默认 record 字符串将一次性秘密写入日志。
     */
    @Override
    public String toString() {
        return "InvitationIssued[invitationId=" + invitationId + "]";
    }
}
