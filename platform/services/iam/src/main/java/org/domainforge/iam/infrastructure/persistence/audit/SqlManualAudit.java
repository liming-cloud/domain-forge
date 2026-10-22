package org.domainforge.iam.infrastructure.persistence.audit;

import java.util.TreeSet;
import java.util.UUID;
import org.domainforge.iam.application.port.audit.ManualAudit;
import org.domainforge.iam.application.port.audit.ManualAuditStore;
import org.springframework.stereotype.Repository;

/** 补录适配只转换受控字段，不附带原始请求或推断结果。 */
@Repository
public class SqlManualAudit implements ManualAuditStore {
  private final ManualAuditMapper mapper;

  /** 使用当前业务事务的审计Mapper。 */
  public SqlManualAudit(ManualAuditMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void append(UUID recordedBy, ManualAudit entry) {
    mapper.append(
        new ManualAuditRow(
            entry.originalOperationId().toString(),
            recordedBy.toString(),
            entry.targetId().toString(),
            entry.action(),
            entry.occurredAt(),
            entry.provenance(),
            String.join(",", new TreeSet<>(entry.missingFields()))));
  }
}
