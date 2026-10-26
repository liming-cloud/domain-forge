package org.domainforge.iam.infrastructure.persistence.audit;

import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计与业务同库同事务写入；写入失败直接抛出，由调用方事务整体回滚。
 */
@Repository
public class SqlAuditTrail implements AuditTrail {
    private final AuditMapper mapper;
    private final Identifiers identifiers;

    /**
     * 使用服务唯一数据源，不另建审计事务或连接。
     */
    public SqlAuditTrail(AuditMapper mapper, Identifiers identifiers) {
        this.mapper = mapper;
        this.identifiers = identifiers;
    }

    /**
     * 必须加入调用方已开启的事务；没有事务时拒绝，防止审计与业务分别提交。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        if (mapper.insert(identifiers.next(), event.actor(), event.targetId(), event.action()) != 1)
            throw new IllegalStateException("审计未写入");
    }
}
