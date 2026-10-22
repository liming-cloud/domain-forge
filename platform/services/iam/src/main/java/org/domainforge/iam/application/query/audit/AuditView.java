package org.domainforge.iam.application.query.audit;

import java.time.Instant;
import java.util.UUID;

/** 审计只读事实包含补录来源与缺失说明，不伪造原始记录。 */
public record AuditView(
    long id,
    UUID operationId,
    String actor,
    UUID targetId,
    String action,
    String outcome,
    Instant occurredAt,
    Instant recordedAt,
    String source,
    String provenance,
    String missingFields) {}
