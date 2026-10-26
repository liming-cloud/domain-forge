package org.domainforge.iam.application.port.audit;

/**
 * 关键管理变更的审计记录；与业务变更同库同事务，审计写入失败使整个业务回滚。
 */
public interface AuditTrail {
    /**
     * 必须在业务事务内调用；失败直接抛出，不重试、不吞掉，也不另起事务。
     */
    void record(AuditEvent event);
}
