package org.domainforge.iam.infrastructure.query.audit;

import org.domainforge.iam.application.query.audit.AuditView;

import java.time.Instant;

/**
 * 审计表投影，字段与查询 SQL 的列顺序一致。
 */
public record AuditProjection(long id, String actor, long targetId, String action, Instant occurredAt) {
    /**
     * 将存储字段转为技术无关视图。
     */
    AuditView view() {
        return new AuditView(id, actor, targetId, action, occurredAt);
    }
}
