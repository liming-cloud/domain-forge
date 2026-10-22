package org.domainforge.iam.infrastructure.persistence.audit;

import java.time.Instant;

/** 补录存储对象仅携带受控字段，actor是实际补录者，outcome固定标为不完整。 */
public record ManualAuditRow(
    String operation,
    String recordedBy,
    String target,
    String action,
    Instant occurredAt,
    String provenance,
    String missingFields) {}
