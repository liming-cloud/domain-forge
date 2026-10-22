package org.domainforge.iam.application.port.audit;

import java.util.UUID;

/** 审计只携带固定动作、可信主体及对象，不接收任意完整请求作为摘要。 */
public record AuditEvent(
    UUID operationId, String actor, UUID targetId, String action, String outcome) {}
