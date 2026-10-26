package org.domainforge.iam.application.port.audit;

/**
 * 审计独立于已完成业务事务；失败必须独立告警，不伪报全量可恢复。
 */
public interface AuditTrail {
    /**
     * 仅在业务提交确认后调用，最多两次有界重试，不因审计故障撤销业务结果。
     */
    void record(AuditEvent event);
}
