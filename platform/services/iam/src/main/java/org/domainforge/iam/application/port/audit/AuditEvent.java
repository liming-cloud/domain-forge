package org.domainforge.iam.application.port.audit;

/**
 * 一次已完成的关键管理变更：可信操作者、目标对象和固定动作名，不携带请求内容或凭据。
 *
 * @param actor    已认证账号 ID，或受控来源标识（deployment-command、invitation-holder）
 * @param targetId 被变更对象的 ID
 * @param action   固定动作名，如 account.permissions
 */
public record AuditEvent(String actor, long targetId, String action) {
    /**
     * 拒绝缺失字段，避免写入无法追溯的审计记录。
     */
    public AuditEvent {
        if (actor == null || actor.isBlank() || targetId <= 0 || action == null || action.isBlank()) {
            throw new IllegalArgumentException("审计事件不完整");
        }
    }
}
