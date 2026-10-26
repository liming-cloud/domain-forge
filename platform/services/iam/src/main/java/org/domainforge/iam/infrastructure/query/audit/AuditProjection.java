package org.domainforge.iam.infrastructure.query.audit;

import org.domainforge.iam.application.query.audit.AuditView;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计表投影隔离驱动UUID转换，缺失信息保持为空而非推断补齐。
 */
public record AuditProjection(
        long id,
        String operationId,
        String actor,
        String targetId,
        String action,
        String outcome,
        Instant occurredAt,
        Instant recordedAt,
        String source,
        String provenance,
        String missingFields) {
    /**
     * 将真实存储字段完整转交技术无关视图。
     */
    AuditView view() {
        return new AuditView(
                id,
                UUID.fromString(operationId),
                actor,
                UUID.fromString(targetId),
                action,
                outcome,
                occurredAt,
                recordedAt,
                source,
                provenance,
                missingFields);
    }
}
