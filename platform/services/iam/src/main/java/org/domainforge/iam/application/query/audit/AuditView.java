package org.domainforge.iam.application.query.audit;

import java.time.Instant;

/**
 * 一条审计事实：操作者、目标、动作与发生时间。
 */
public record AuditView(long id, String actor, long targetId, String action, Instant occurredAt) {
}
