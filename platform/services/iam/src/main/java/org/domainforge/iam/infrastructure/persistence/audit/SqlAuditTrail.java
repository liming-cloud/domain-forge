package org.domainforge.iam.infrastructure.persistence.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 普通审计独立提交，失败最多重试两次并保留独立告警，不回滚已成功业务。 */
@Repository
public class SqlAuditTrail implements AuditTrail {
  private static final Logger LOG = LoggerFactory.getLogger("iam.audit.failure");
  private final AuditMapper mapper;
  private final TransactionTemplate transaction;
  private final Counter failures;

  /** 不创建第二个数据源；REQUIRES_NEW隔离审计事务及错误。 */
  public SqlAuditTrail(
      AuditMapper mapper, PlatformTransactionManager manager, MeterRegistry metrics) {
    this.mapper = mapper;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(1);
    failures = metrics.counter("iam.audit.incomplete");
  }

  /** 日志只包含关联ID和异常类别，绝不输出SQL参数或原始请求。 */
  @Override
  public void record(AuditEvent event) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        transaction.execute(
            status -> {
              mapper.budget();
              return mapper.insert(
                  event.operationId().toString(),
                  event.actor(),
                  event.targetId().toString(),
                  event.action(),
                  event.outcome());
            });
        return;
      } catch (RuntimeException failure) {
        if (attempt == 2) {
          failures.increment();
          LOG.error(
              "audit_incomplete operationId={} action={} failureType={}",
              event.operationId(),
              event.action(),
              failure.getClass().getSimpleName());
        }
      }
    }
  }
}
