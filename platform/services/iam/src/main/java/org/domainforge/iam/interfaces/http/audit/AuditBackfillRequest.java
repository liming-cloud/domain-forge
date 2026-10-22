package org.domainforge.iam.interfaces.http.audit;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 受控人工补录只接受证据引用及缺失字段，不接受密码或完整业务请求。 */
public record AuditBackfillRequest(
    UUID operationId,
    UUID originalOperationId,
    UUID targetId,
    String action,
    Instant occurredAt,
    String provenance,
    Set<String> missingFields) {}
